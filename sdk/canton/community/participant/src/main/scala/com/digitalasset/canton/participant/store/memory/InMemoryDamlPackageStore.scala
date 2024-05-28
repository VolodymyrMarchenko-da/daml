// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.store.memory

import cats.Monoid
import cats.data.OptionT
import com.daml.daml_lf_dev.DamlLf
import com.daml.lf.data.Ref.PackageId
import com.digitalasset.canton.LfPackageId
import com.digitalasset.canton.config.CantonRequireTypes.{String255, String256M}
import com.digitalasset.canton.crypto.Hash
import com.digitalasset.canton.data.CantonTimestamp
import com.digitalasset.canton.discard.Implicits.DiscardOps
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.logging.{NamedLoggerFactory, NamedLogging}
import com.digitalasset.canton.participant.admin.PackageService
import com.digitalasset.canton.participant.admin.PackageService.{Dar, DarDescriptor}
import com.digitalasset.canton.participant.store.DamlPackageStore
import com.digitalasset.canton.participant.store.memory.InMemoryDamlPackageStore.defaultPackageDescription
import com.digitalasset.canton.protocol.PackageDescription
import com.digitalasset.canton.tracing.TraceContext

import java.util.concurrent.ConcurrentHashMap
import scala.collection.concurrent
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters.*

class InMemoryDamlPackageStore(override protected val loggerFactory: NamedLoggerFactory)(implicit
    ec: ExecutionContext
) extends DamlPackageStore
    with NamedLogging {
  import DamlPackageStore.*

  private val pkgData
      : concurrent.Map[LfPackageId, (DamlLf.Archive, String256M, CantonTimestamp, Int)] =
    new ConcurrentHashMap[LfPackageId, (DamlLf.Archive, String256M, CantonTimestamp, Int)].asScala

  private val darData: concurrent.Map[Hash, (Array[Byte], String255)] =
    new ConcurrentHashMap[Hash, (Array[Byte], String255)].asScala

  private val darPackages: concurrent.Map[Hash, Set[LfPackageId]] =
    new ConcurrentHashMap[Hash, Set[LfPackageId]].asScala

  override def append(
      pkgs: List[DamlLf.Archive],
      uploadedAt: CantonTimestamp,
      sourceDescription: String256M,
      dar: Option[PackageService.Dar],
  )(implicit
      traceContext: TraceContext
  ): FutureUnlessShutdown[Unit] = {

    val pkgIds = pkgs.map(readPackageId)

    pkgs.foreach { pkgArchive =>
      val packageId = readPackageId(pkgArchive)
      val packageSize = pkgArchive.getPayload.size()
      // only update the description if the given one is not empty
      if (sourceDescription.nonEmpty)
        pkgData
          .put(packageId, (pkgArchive, sourceDescription, uploadedAt, packageSize))
          .discard
      else
        pkgData
          .updateWith(packageId) {
            case None => Some((pkgArchive, defaultPackageDescription, uploadedAt, packageSize))
            case Some((_, oldDescription, _, _)) =>
              Some((pkgArchive, oldDescription, uploadedAt, packageSize))
          }
          .discard
    }

    dar.foreach { dar =>
      darData.put(dar.descriptor.hash, (dar.bytes.clone(), dar.descriptor.name)).discard
      val hash = dar.descriptor.hash
      val pkgS = pkgIds.toSet
      darPackages.updateWith(hash)(optSet => Some(optSet.fold(pkgS)(_.union(pkgS))))
    }

    FutureUnlessShutdown.unit
  }

  override def getPackage(packageId: LfPackageId)(implicit
      traceContext: TraceContext
  ): Future[Option[DamlLf.Archive]] =
    Future.successful(pkgData.get(packageId).map(_._1))

  override def getPackageDescription(
      packageId: LfPackageId
  )(implicit traceContext: TraceContext): Future[Option[PackageDescription]] =
    Future.successful(
      pkgData.get(packageId).map { case (_, sourceDescription, uploadedAt, packageSize) =>
        PackageDescription(packageId, sourceDescription, uploadedAt, packageSize)
      }
    )

  override def listPackages(
      limit: Option[Int]
  )(implicit traceContext: TraceContext): Future[Seq[PackageDescription]] =
    Future.successful(
      pkgData
        .take(limit.getOrElse(Int.MaxValue))
        .map { case (pid, (_, sourceDescription, uploadedAt, packageSize)) =>
          PackageDescription(pid, sourceDescription, uploadedAt, packageSize)
        }
        .to(Seq)
    )

  override def removePackage(
      packageId: PackageId
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit] = {
    darPackages
      .mapValuesInPlace({ case (_hash, packages) => packages - packageId })
      .filterInPlace({ case (_hash, packages) => packages.nonEmpty })
      .discard

    pkgData.remove(packageId).discard

    FutureUnlessShutdown.unit
  }

  override def getDar(
      hash: Hash
  )(implicit traceContext: TraceContext): Future[Option[Dar]] =
    Future.successful(darData.get(hash).map { case (bytes, name) =>
      Dar(DarDescriptor(hash, name), bytes.clone())
    })

  override def listDars(
      limit: Option[Int]
  )(implicit traceContext: TraceContext): Future[Seq[DarDescriptor]] =
    Future.successful(
      darData
        .take(limit.getOrElse(Int.MaxValue))
        .map { case (hash, (_, name)) =>
          DarDescriptor(hash, name)
        }
        .to(Seq)
    )

  override def anyPackagePreventsDarRemoval(packages: Seq[PackageId], removeDar: DarDescriptor)(
      implicit tc: TraceContext
  ): OptionT[Future, PackageId] = {
    val known = packages.toSet.intersect(Monoid.combineAll(darPackages.toMap.values))
    val fromAllOtherDars = Monoid.combineAll(darPackages.toMap.removed(removeDar.hash).values)
    val withoutDar = known.diff(fromAllOtherDars).headOption
    OptionT.fromOption(withoutDar)
  }

  override def determinePackagesExclusivelyInDar(
      packages: Seq[PackageId],
      removeDar: DarDescriptor,
  )(implicit tc: TraceContext): Future[Seq[PackageId]] = {
    val packagesInOtherDars = Monoid.combineAll(darPackages.toMap.removed(removeDar.hash).values)
    val packagesNotInAnyOtherDars = packages.toSet.diff(packagesInOtherDars)
    Future.successful(packagesNotInAnyOtherDars.toSeq)
  }

  override def removeDar(
      hash: Hash
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit] = {
    darPackages.remove(hash).discard
    darData.remove(hash).discard
    FutureUnlessShutdown.unit
  }

  override def close(): Unit = ()
}

object InMemoryDamlPackageStore {
  val defaultPackageDescription = String256M.tryCreate("default")
}
