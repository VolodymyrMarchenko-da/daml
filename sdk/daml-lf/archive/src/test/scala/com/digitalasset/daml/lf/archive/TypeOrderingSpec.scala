// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.daml.lf.archive

import com.digitalasset.daml.lf.archive.DamlLf2
import com.digitalasset.daml.lf.language.{Ast, TypeOrdering}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.jdk.CollectionConverters._

class TypeOrderingSpec extends AnyWordSpec with Matchers {

  "TypeOrdering" should {

    "follow archive protobuf order for LF2" in {

      val protoMapping =
        DecodeV2.builtinTypeInfos.iterator.map(info => info.proto -> info.bTyp).toMap

      val primTypesInProtoOrder =
        DamlLf2.BuiltinType.getDescriptor.getValues.asScala
          .map(desc => DamlLf2.BuiltinType.internalGetValueMap().findValueByNumber(desc.getNumber))
          .sortBy(_.getNumber)
          .collect(protoMapping)

      primTypesInProtoOrder.sortBy(Ast.TBuiltin)(
        TypeOrdering.compare _
      ) shouldBe primTypesInProtoOrder
    }
  }

}
