// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.lf
package engine

import com.daml.lf.command._
import com.daml.lf.data._
import com.daml.lf.data.Ref.{PackageId, ParticipantId, Party}
import com.daml.lf.language.Ast._
import com.daml.lf.speedy.{InitialSeeding, PartialTransaction, Pretty, SExpr}
import com.daml.lf.speedy.Speedy.Machine
import com.daml.lf.speedy.SResult._
import com.daml.lf.transaction.{NodeId, SubmittedTransaction, Transaction => Tx}
import com.daml.lf.transaction.Node._
import com.daml.lf.value.Value
import java.nio.file.Files

/**
  * Allows for evaluating [[Commands]] and validating [[Transaction]]s.
  * <p>
  *
  * This class does not dereference contract ids or package ids on its own.
  * Instead, when an instance of this class needs to dereference a contract id or package id,
  * it returns a [[ResultNeedContract]] or [[ResultNeedPackage]] to the caller.
  * The caller can then resume the computation by calling `result.resume`.
  * The engine may or may not cache and reuse the provided contract instance or package.
  * <p>
  *
  * The caller must dereference contract and package ids consistently, i.e.,
  * if the '''same engine''' returns `result1` and `result2`,
  * `result1` and `result2` request to dereference the same contract or package id, and
  * the caller invokes `result1.resume(x1)` and `result2.resume(x2)`,
  * then `x1` must equal `x2`.
  * <p>
  *
  * The caller may deference ids inconsistently across different engines.
  * Namely, if '''two different engines''' return `result1` and `result2`,
  * then the caller may call `result1.resume(x1)` and `result2.resume(x2)` with `x1 != x2`,
  * even if `result1` and `result2` request to dereference the same id.
  * <p>
  *
  * The class requires a pseudo random generator (`nextRandomInt`) to randomize the
  * submission time. This generator does not have to be cryptographically secure.
  * <p>
  *
  * This class is thread safe as long `nextRandomInt` is.
  */
class Engine(val config: EngineConfig = EngineConfig.Stable) {

  config.profileDir.foreach(Files.createDirectories(_))

  private[this] val compiledPackages =
    ConcurrentCompiledPackages(
      allowedLanguageVersions = config.allowedLanguageVersions,
      packageValidation =
        if (config.packageValidation) speedy.Compiler.FullPackageValidation
        else speedy.Compiler.NoPackageValidation,
      stackTraceMode =
        if (config.stackTraceMode) speedy.Compiler.FullStackTrace else speedy.Compiler.NoStackTrace,
      profilingMode =
        if (config.profileDir.isDefined) speedy.Compiler.FullProfile else speedy.Compiler.NoProfile,
    )

  private[this] val preprocessor = new preprocessing.Preprocessor(compiledPackages)

  def info = new EngineInfo(config)

  /**
    * Executes commands `cmds` under the authority of `cmds.submitter` and returns one of the following:
    * <ul>
    * <li> `ResultDone(tx)` if `cmds` could be successfully executed, where `tx` is the resulting transaction.
    *      The transaction `tx` conforms to the DAML model consisting of the packages that have been supplied via
    *      `ResultNeedPackage.resume` to this [[Engine]].
    *      The transaction `tx` is internally consistent.
    * </li>
    * <li> `ResultNeedContract(contractId, resume)` if the contract referenced by `contractId` is needed to execute
    *      `cmds`.
    * </li>
    * <li> `ResultNeedPackage(packageId, resume)` if the package referenced by `packageId` is needed to execute `cmds`.
    * </li>
    * <li> `ResultError` if the execution of `cmds` fails.
    *      The execution may fail due to an error during DAML evaluation (e.g. execution of "abort") or
    *      because the caller has not provided a required contract instance or package.
    * </li>
    * </ul>
    *
    *
    * [[transactionSeed]] is the master hash used to derive node and contractId discriminator.
    * If left undefined, no discriminator will be generated.
    *
    * This method does NOT perform authorization checks; ResultDone can contain a transaction that's not well-authorized.
    *
    * The resulting transaction is annotated with packages required to validate it.
    */
  def submit(
      cmds: Commands,
      participantId: ParticipantId,
      submissionSeed: crypto.Hash,
  ): Result[(SubmittedTransaction, Tx.Metadata)] = {
    val submissionTime = cmds.ledgerEffectiveTime
    preprocessor
      .preprocessCommands(cmds.commands)
      .flatMap {
        case (processedCmds, globalCids) =>
          interpretCommands(
            validating = false,
            submitters = Set(cmds.submitter),
            commands = processedCmds,
            ledgerTime = cmds.ledgerEffectiveTime,
            submissionTime = submissionTime,
            seeding = Engine.initialSeeding(submissionSeed, participantId, submissionTime),
            globalCids,
          ) map {
            case (tx, meta) =>
              // Annotate the transaction with the package dependencies. Since
              // all commands are actions on a contract template, with a fully typed
              // argument, we only need to consider the templates mentioned in the command
              // to compute the full dependencies.
              val deps = processedCmds.foldLeft(Set.empty[PackageId]) { (pkgIds, cmd) =>
                val pkgId = cmd.templateId.packageId
                val transitiveDeps =
                  compiledPackages
                    .getPackageDependencies(pkgId)
                    .getOrElse(sys.error(s"INTERNAL ERROR: Missing dependencies of package $pkgId"))
                (pkgIds + pkgId) union transitiveDeps
              }
              tx -> meta.copy(submissionSeed = Some(submissionSeed), usedPackages = deps)
          }
      }
  }

  /**
    * Behaves like `submit`, but it takes a GenNode argument instead of a Commands argument.
    * That is, it can be used to reinterpret partially an already interpreted transaction (since it consists of GenNodes).
    *
    *
    * [[nodeSeed]] is the seed of the Create and Exercise node as generated during submission.
    * If undefined the contract IDs are derive using V0 scheme.
    * The value of [[nodeSeed]] does not matter for other kind of nodes.
    *
    * The reinterpretation does not recompute the package dependencies, so the field `usedPackages` in the
    * `Tx.MetaData` component of the output is always set to `empty`.
    */
  def reinterpret(
      submitters: Set[Party],
      node: GenNode.WithTxValue[NodeId, Value.ContractId],
      nodeSeed: Option[crypto.Hash],
      submissionTime: Time.Timestamp,
      ledgerEffectiveTime: Time.Timestamp,
  ): Result[(SubmittedTransaction, Tx.Metadata)] =
    for {
      commandWithCids <- preprocessor.translateNode(node)
      (command, globalCids) = commandWithCids
      // reinterpret is never used for submission, only for validation.
      result <- interpretCommands(
        validating = true,
        submitters = submitters,
        commands = ImmArray(command),
        ledgerTime = ledgerEffectiveTime,
        submissionTime = submissionTime,
        seeding = InitialSeeding.RootNodeSeeds(ImmArray(nodeSeed)),
        globalCids,
      )
      (tx, meta) = result
    } yield (tx, meta)

  def replay(
      submitter: Party,
      tx: SubmittedTransaction,
      ledgerEffectiveTime: Time.Timestamp,
      participantId: Ref.ParticipantId,
      submissionTime: Time.Timestamp,
      submissionSeed: crypto.Hash,
  ): Result[(SubmittedTransaction, Tx.Metadata)] =
    for {
      commandsWithCids <- preprocessor.translateTransactionRoots(tx.transaction)
      (commands, globalCids) = commandsWithCids
      result <- interpretCommands(
        validating = true,
        submitters = Set(submitter),
        commands = commands,
        ledgerTime = ledgerEffectiveTime,
        submissionTime = submissionTime,
        seeding = Engine.initialSeeding(submissionSeed, participantId, submissionTime),
        globalCids,
      )

    } yield result

  /**
    * Check if the given transaction is a valid result of some single-submitter command.
    *
    * Formally, for all tx, pcs, pkgs, keys:
    *   evaluate(validate(tx, ledgerEffectiveTime)) == ResultDone(()) <==> exists cmds. evaluate(submit(cmds)) = tx
    * where:
    *   evaluate(result) = result.consume(pcs, pkgs, keys)
    *
    * A transaction may contain relative contract IDs and still pass validation, but not in the root nodes.
    *
    * This is enforced since commands cannot contain relative contract ids, and we check that root nodes come from commands.
    *
    *  @param tx a complete unblinded Transaction to be validated
    *  @param ledgerEffectiveTime time when the transaction is claimed to be submitted
    */
  def validate(
      submitter: Party,
      tx: SubmittedTransaction,
      ledgerEffectiveTime: Time.Timestamp,
      participantId: Ref.ParticipantId,
      submissionTime: Time.Timestamp,
      submissionSeed: crypto.Hash,
  ): Result[Unit] = {
    //reinterpret
    for {
      result <- replay(
        submitter,
        tx,
        ledgerEffectiveTime,
        participantId,
        submissionTime,
        submissionSeed,
      )
      (rtx, _) = result
      validationResult <- if (tx.transaction isReplayedBy rtx.transaction) {
        ResultDone.Unit
      } else {
        ResultError(
          ValidationError(
            s"recreated and original transaction mismatch $tx expected, but $rtx is recreated"))
      }
    } yield validationResult
  }

  private def loadPackages(pkgIds: List[PackageId]): Result[Unit] =
    pkgIds.dropWhile(compiledPackages.packages.isDefinedAt) match {
      case pkgId :: rest =>
        ResultNeedPackage(pkgId, {
          case Some(pkg) =>
            compiledPackages.addPackage(pkgId, pkg).flatMap(_ => loadPackages(rest))
          case None =>
            ResultError(Error(s"package $pkgId not found"))
        })
      case Nil =>
        ResultDone.Unit
    }

  @inline
  private[lf] def runSafely[X](handleMissingDependencies: => Result[Unit])(
      run: => Result[X]): Result[X] = {
    def start: Result[X] =
      try {
        run
      } catch {
        case speedy.Compiler.PackageNotFound(_) =>
          handleMissingDependencies.flatMap(_ => start)
        case speedy.Compiler.CompilationError(error) =>
          ResultError(Error(s"CompilationError: $error"))
      }
    start
  }

  /** Interprets the given commands under the authority of @submitters
    *
    * Submitters are a set, in order to support interpreting subtransactions
    * (a subtransaction can be authorized by multiple parties).
    *
    * [[seeding]] is seeding used to derive node seed and contractId discriminator.
    *
    */
  private[engine] def interpretCommands(
      validating: Boolean,
      /* See documentation for `Speedy.Machine` for the meaning of this field */
      submitters: Set[Party],
      commands: ImmArray[speedy.Command],
      ledgerTime: Time.Timestamp,
      submissionTime: Time.Timestamp,
      seeding: speedy.InitialSeeding,
      globalCids: Set[Value.ContractId],
  ): Result[(SubmittedTransaction, Tx.Metadata)] =
    runSafely(
      loadPackages(commands.foldLeft(Set.empty[PackageId])(_ + _.templateId.packageId).toList)
    ) {
      val sexpr = compiledPackages.compiler.unsafeCompile(commands)
      val machine = Machine(
        compiledPackages = compiledPackages,
        submissionTime = submissionTime,
        initialSeeding = seeding,
        expr = SExpr.SEApp(sexpr, Array(SExpr.SEValue.Token)),
        globalCids = globalCids,
        committers = submitters,
        inputValueVersions = config.allowedInputValueVersions,
        outputTransactionVersions = config.allowedOutputTransactionVersions,
        validating = validating,
      )
      interpretLoop(machine, ledgerTime)
    }

  // TODO SC remove 'return', notwithstanding a love of unhandled exceptions
  @SuppressWarnings(Array("org.wartremover.warts.Return"))
  private[engine] def interpretLoop(
      machine: Machine,
      time: Time.Timestamp
  ): Result[(SubmittedTransaction, Tx.Metadata)] = {
    var finished: Boolean = false
    while (!finished) {
      machine.run() match {
        case SResultFinalValue(_) => finished = true

        case SResultError(err) =>
          return ResultError(
            Error(
              s"Interpretation error: ${Pretty.prettyError(err, machine.ptx).render(80)}",
              s"Last location: ${Pretty.prettyLoc(machine.lastLocation).render(80)}, partial transaction: ${machine.ptx.nodesToString}"
            ))

        case SResultNeedPackage(pkgId, callback) =>
          return Result.needPackage(
            pkgId,
            pkg => {
              compiledPackages.addPackage(pkgId, pkg).flatMap { _ =>
                callback(compiledPackages)
                interpretLoop(machine, time)
              }
            }
          )

        case SResultNeedContract(contractId, _, _, _, cbPresent) =>
          return Result.needContract(
            contractId, { coinst =>
              cbPresent(coinst)
              interpretLoop(machine, time)
            }
          )

        case SResultNeedTime(callback) =>
          machine.dependsOnTime = true
          callback(time)

        case SResultNeedKey(gk, _, cb) =>
          return ResultNeedKey(
            gk,
            result =>
              if (cb(SKeyLookupResult(result)))
                interpretLoop(machine, time)
              else
                ResultError(Error(s"dependency error: couldn't find key ${gk.globalKey}"))
          )

        case _: SResultScenarioCommit =>
          return ResultError(Error("unexpected ScenarioCommit"))

        case _: SResultScenarioInsertMustFail =>
          return ResultError(Error("unexpected ScenarioInsertMustFail"))
        case _: SResultScenarioMustFail =>
          return ResultError(Error("unexpected ScenarioMustFail"))
        case _: SResultScenarioPassTime =>
          return ResultError(Error("unexpected ScenarioPassTime"))
        case _: SResultScenarioGetParty =>
          return ResultError(Error("unexpected ScenarioGetParty"))
      }
    }

    machine.ptx.finish(
      machine.outputTransactionVersions,
      compiledPackages.packageLanguageVersion,
    ) match {
      case PartialTransaction.CompleteTransaction(tx) =>
        val meta = Tx.Metadata(
          submissionSeed = None,
          submissionTime = machine.ptx.submissionTime,
          usedPackages = Set.empty,
          dependsOnTime = machine.dependsOnTime,
          nodeSeeds = machine.ptx.nodeSeeds.toImmArray,
          byKeyNodes = machine.ptx.byKeyNodes.toImmArray,
        )
        config.profileDir.foreach { dir =>
          val hash = meta.nodeSeeds(0)._2.toHexString
          val desc = Engine.profileDesc(tx)
          machine.profile.name = s"$desc-${hash.substring(0, 6)}"
          val profileFile = dir.resolve(s"${meta.submissionTime}-$desc-$hash.json")
          machine.profile.writeSpeedscopeJson(profileFile)
        }
        ResultDone((tx, meta))
      case PartialTransaction.IncompleteTransaction(ptx) =>
        ResultError(Error(s"Interpretation error: ended with partial result: $ptx"))
      case PartialTransaction.SerializationError(msg) =>
        ResultError(SerializationError(msg))
    }
  }

  def clearPackages(): Unit = compiledPackages.clear()

  /** Note: it's important we return a [[com.daml.lf.CompiledPackages]],
    * and not a [[ConcurrentCompiledPackages]], otherwise people would be able
    * to modify them.
    */
  def compiledPackages(): CompiledPackages = compiledPackages

  /** This function can be used to give a package to the engine pre-emptively,
    * rather than having the engine to ask about it through
    * [[ResultNeedPackage]].
    *
    * Returns a [[Result]] because the package might need another package to
    * be loaded.
    */
  def preloadPackage(pkgId: PackageId, pkg: Package): Result[Unit] =
    compiledPackages.addPackage(pkgId, pkg)

}

object Engine {

  def initialSeeding(
      submissionSeed: crypto.Hash,
      participant: Ref.ParticipantId,
      submissionTime: Time.Timestamp,
  ): InitialSeeding =
    InitialSeeding.TransactionSeed(
      crypto.Hash.deriveTransactionSeed(submissionSeed, participant, submissionTime))

  private def profileDesc(tx: Tx.Transaction): String = {
    if (tx.roots.length == 1) {
      val makeDesc = (kind: String, tmpl: Ref.Identifier, extra: Option[String]) =>
        s"$kind:${tmpl.qualifiedName.name}${extra.map(extra => s":$extra").getOrElse("")}"
      tx.nodes.get(tx.roots(0)).toList.head match {
        case create: NodeCreate[_, _] => makeDesc("create", create.coinst.template, None)
        case exercise: NodeExercises[_, _, _] =>
          makeDesc("exercise", exercise.templateId, Some(exercise.choiceId.toString))
        case fetch: NodeFetch[_, _] => makeDesc("fetch", fetch.templateId, None)
        case lookup: NodeLookupByKey[_, _] => makeDesc("lookup", lookup.templateId, None)
      }
    } else {
      s"compound:${tx.roots.length}"
    }
  }

  def DevEngine(): Engine = new Engine(EngineConfig.Dev)

}
