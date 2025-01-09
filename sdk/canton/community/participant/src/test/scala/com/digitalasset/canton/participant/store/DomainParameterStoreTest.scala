// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.store

import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.topology.{SynchronizerId, UniqueIdentifier}
import com.digitalasset.canton.version.{InUS, ProtocolVersion}
import org.scalatest.wordspec.AsyncWordSpec

trait DomainParameterStoreTest extends InUS { this: AsyncWordSpec & BaseTest =>

  private val synchronizerId = SynchronizerId(
    UniqueIdentifier.tryFromProtoPrimitive("synchronizerId::synchronizerId")
  )

  private def anotherProtocolVersion(testedProtocolVersion: ProtocolVersion): ProtocolVersion =
    if (testedProtocolVersion.isDev)
      ProtocolVersion.minimum
    else
      ProtocolVersion.dev

  def domainParameterStore(mk: SynchronizerId => SynchronizerParameterStore): Unit = {

    "setParameters" should {
      "store new parameters" inUS {
        val store = mk(synchronizerId)
        val params = defaultStaticSynchronizerParameters
        for {
          _ <- store.setParameters(params)
          last <- store.lastParameters
        } yield {
          last shouldBe Some(params)
        }
      }

      "be idempotent" inUS {
        val store = mk(synchronizerId)
        val params =
          BaseTest.defaultStaticSynchronizerParametersWith(protocolVersion =
            anotherProtocolVersion(testedProtocolVersion)
          )
        for {
          _ <- store.setParameters(params)
          _ <- store.setParameters(params)
          last <- store.lastParameters
        } yield {
          last shouldBe Some(params)
        }
      }

      "not overwrite changed domain parameters" inUS {
        val store = mk(synchronizerId)
        val params = defaultStaticSynchronizerParameters
        val modified =
          BaseTest.defaultStaticSynchronizerParametersWith(protocolVersion =
            anotherProtocolVersion(testedProtocolVersion)
          )
        for {
          _ <- store.setParameters(params)
          ex <- store.setParameters(modified).failed
          last <- store.lastParameters
        } yield {
          ex shouldBe an[IllegalArgumentException]
          last shouldBe Some(params)
        }
      }
    }

    "lastParameters" should {
      "return None for the empty store" inUS {
        val store = mk(synchronizerId)
        for {
          last <- store.lastParameters
        } yield {
          last shouldBe None
        }
      }
    }
  }
}
