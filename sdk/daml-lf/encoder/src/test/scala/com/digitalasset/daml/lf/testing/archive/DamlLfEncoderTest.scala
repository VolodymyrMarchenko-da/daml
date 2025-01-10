// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.daml.lf.testing.archive

import java.io.File
import com.daml.bazeltools.BazelRunfiles
import com.digitalasset.daml.lf.archive.DamlLf2
import com.digitalasset.daml.lf.archive.{
  ArchivePayload,
  Dar,
  DecodeV2,
  UniversalArchiveDecoder,
  UniversalArchiveReader,
}
import com.digitalasset.daml.lf.data.Ref.DottedName
import com.digitalasset.daml.lf.data.Ref.ModuleName
import com.digitalasset.daml.lf.language.Ast
import com.digitalasset.daml.lf.language.LanguageVersion
import org.scalatest.prop.TableDrivenPropertyChecks
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.jdk.CollectionConverters._
import scala.language.implicitConversions
import scala.Ordering.Implicits.infixOrderingOps

class DamlLfEncoderTest
    extends AnyWordSpec
    with Matchers
    with TableDrivenPropertyChecks
    with BazelRunfiles {

  "dar generated by encoder" should {

    "be readable" in {

      val modules_2_1 = Set[DottedName](
        "UnitMod",
        "BoolMod",
        "Int64Mod",
        "TextMod",
        "DateMod",
        "TimestampMod",
        "ListMod",
        "PartyMod",
        "RecordMod",
        "VariantMod",
        "BuiltinMod",
        "TemplateMod",
        "OptionMod",
        "EnumMod",
        "NumericMod",
        "AnyMod",
        "SynonymMod",
        "GenMapMod",
        "ExceptionMod",
        "InterfaceMod",
        "InterfaceMod0",
      )
      val modules_2_dev = modules_2_1 ++ Set[DottedName](
        "TextMapMod",
        "BigNumericMod",
        "InterfaceExtMod",
      )

      val versions = Table(
        "versions" -> "modules",
        "2.1" -> modules_2_1,
        "2.dev" -> modules_2_dev,
      )

      forEvery(versions) { (version, expectedModules) =>
        val dar =
          UniversalArchiveReader
            .readFile(new File(rlocation(s"daml-lf/encoder/test-$version.dar")))

        dar shouldBe a[Right[_, _]]

        val findModules = dar.toOption.toList.flatMap(getNonEmptyModules).toSet

        findModules diff expectedModules shouldBe Set()
        expectedModules diff findModules shouldBe Set()

      }
    }

  }

  private def getNonEmptyModules(dar: Dar[ArchivePayload]): Seq[ModuleName] = {
    for {
      payload <- dar.all
      name <- payload match {
        case ArchivePayload.Lf2(_, pkg, _) => getNonEmptyModules(pkg)
        case _ => throw new RuntimeException(s"Unsupported language version: ${payload.version}")
      }
    } yield name
  }

  private def getNonEmptyModules(pkg: DamlLf2.Package): Seq[DottedName] = {
    val internedStrings = pkg.getInternedStringsList.asScala.toArray
    val dottedNames = pkg.getInternedDottedNamesList.asScala.map(
      _.getSegmentsInternedStrList.asScala.map(internedStrings(_))
    )
    for {
      segments <- pkg.getModulesList.asScala.toSeq.map {
        case mod
            if mod.getSynonymsCount != 0 ||
              mod.getDataTypesCount != 0 ||
              mod.getValuesCount != 0 ||
              mod.getTemplatesCount != 0 =>
          dottedNames(mod.getNameInternedDname)
      }
    } yield DottedName.assertFromSegments(segments)
  }

  "BuiltinMod" should {

    val builtinMod = ModuleName.assertFromString("BuiltinMod")

    "contains all builtins " in {
      forEvery(Table("version", LanguageVersion.AllV2.filter(LanguageVersion.v2_1 <= _): _*)) {
        version =>
          val Right(dar) =
            UniversalArchiveDecoder
              .readFile(new File(rlocation(s"daml-lf/encoder/test-${version.pretty}.dar")))
          val (_, mainPkg) = dar.main
          val builtinInModule = mainPkg
            .modules(builtinMod)
            .definitions
            .values
            .collect { case Ast.DValue(_, Ast.EBuiltinFun(builtin), _) => builtin }
            .toSet
          val builtinsInVersion = DecodeV2.builtinFunctionInfos.collect {
            case DecodeV2.BuiltinFunctionInfo(_, builtin, minVersion, maxVersion, _)
                if minVersion <= version && maxVersion.forall(version < _) =>
              builtin
          }.toSet

          val missingBuiltins = builtinsInVersion -- builtinInModule
          assert(missingBuiltins.isEmpty, s", missing builtin(s) in BuiltinMod")
          val unexpetedBuiltins = builtinInModule -- builtinsInVersion
          assert(unexpetedBuiltins.isEmpty, s", unexpected builtin(s) in BuiltinMod")
      }
    }
  }

  private implicit def toDottedName(s: String): DottedName =
    DottedName.assertFromString(s)

}
