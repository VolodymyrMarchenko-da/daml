// Copyright (c) 2020 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.ledger.validator

import com.daml.ledger.participant.state.kvutils.DamlKvutils.{
  DamlPartyAllocation,
  DamlStateKey,
  DamlStateValue
}
import com.daml.ledger.participant.state.kvutils.Envelope
import com.daml.ledger.validator.LedgerStateOperations.Key
import com.daml.ledger.validator.RawToDamlLedgerStateReaderAdapterSpec._
import com.daml.ledger.validator.TestHelper.{anInvalidEnvelope, makePartySubmission}
import org.mockito.{ArgumentMatchersSugar, MockitoSugar}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import scala.concurrent.{ExecutionContext, Future}

class RawToDamlLedgerStateReaderAdapterSpec
    extends AsyncWordSpec
    with Matchers
    with MockitoSugar
    with ArgumentMatchersSugar {
  "readState" should {
    "read the right key and deserialize it" in {
      val expectedKey = DefaultStateKeySerializationStrategy.serializeStateKey(aDamlStateKey())
      val expectedValue = DamlStateValue.newBuilder
        .setParty(DamlPartyAllocation.newBuilder.setDisplayName("aParty"))
        .build
      val mockReader = mock[LedgerStateReader]
      when(mockReader.read(any[Seq[Key]])(anyExecutionContext))
        .thenReturn(Future.successful(Seq(Some(Envelope.enclose(expectedValue)))))
      val instance =
        new RawToDamlLedgerStateReaderAdapter(mockReader, DefaultStateKeySerializationStrategy)

      instance.readState(Seq(aDamlStateKey())).map { actual =>
        verify(mockReader, times(1)).read(Seq(expectedKey))
        actual shouldBe Seq(Some(expectedValue))
      }
    }

    "throw in case of an invalid envelope returned from underlying reader" in {
      val mockReader = mock[LedgerStateReader]
      when(mockReader.read(any[Seq[Key]])(anyExecutionContext))
        .thenReturn(Future.successful(Seq(Some(anInvalidEnvelope))))
      val instance =
        new RawToDamlLedgerStateReaderAdapter(mockReader, DefaultStateKeySerializationStrategy)

      instance.readState(Seq(aDamlStateKey())).failed.map { actual =>
        actual shouldBe a[RuntimeException]
        actual.getLocalizedMessage should include("Opening enveloped")
      }
    }

    "throw in case an enveloped value other than a DamlStateValue is returned from underlying reader" in {
      val notADamlStateValue = makePartySubmission("aParty")
      val mockReader = mock[LedgerStateReader]
      when(mockReader.read(any[Seq[Key]])(anyExecutionContext))
        .thenReturn(Future.successful(Seq(Some(Envelope.enclose(notADamlStateValue)))))
      val instance =
        new RawToDamlLedgerStateReaderAdapter(mockReader, DefaultStateKeySerializationStrategy)

      instance.readState(Seq(aDamlStateKey())).failed.map { actual =>
        actual shouldBe a[RuntimeException]
        actual.getLocalizedMessage should include("Opening enveloped")
      }
    }
  }
}

object RawToDamlLedgerStateReaderAdapterSpec {

  import ArgumentMatchersSugar._

  private def anyExecutionContext = any[ExecutionContext]

  private def aDamlStateKey(): DamlStateKey =
    DamlStateKey.newBuilder
      .setContractId("aContractId")
      .build
}
