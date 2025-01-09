// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.participant.store.db

import com.daml.nameof.NameOf.functionFullName
import com.digitalasset.canton.BaseTest
import com.digitalasset.canton.lifecycle.FutureUnlessShutdown
import com.digitalasset.canton.participant.store.ActiveContractStoreTest
import com.digitalasset.canton.participant.store.db.DbActiveContractStoreTest.maxDomainIndex
import com.digitalasset.canton.participant.store.db.DbContractStoreTest.createDbContractStoreForTesting
import com.digitalasset.canton.resource.DbStorage
import com.digitalasset.canton.store.db.{DbTest, H2Test, PostgresTest}
import com.digitalasset.canton.store.memory.InMemoryIndexedStringStore
import com.digitalasset.canton.store.{
  IndexedStringType,
  IndexedSynchronizer,
  PrunableByTimeParameters,
}
import com.digitalasset.canton.tracing.TraceContext
import org.scalatest.wordspec.AsyncWordSpec

trait DbActiveContractStoreTest extends AsyncWordSpec with BaseTest with ActiveContractStoreTest {
  this: DbTest =>

  private val domainIndex = 1

  override def cleanDb(
      storage: DbStorage
  )(implicit traceContext: TraceContext): FutureUnlessShutdown[Unit] = {
    import storage.api.*
    storage.update(
      DBIO.seq(
        sqlu"truncate table par_active_contracts",
        sqlu"truncate table par_active_contract_pruning",
        sqlu"delete from par_contracts",
      ),
      functionFullName,
    )
  }

  "DbActiveContractStore" should {
    behave like activeContractStore(
      ec => {
        val indexStore = new InMemoryIndexedStringStore(minIndex = 1, maxIndex = maxDomainIndex)

        val synchronizerId = IndexedSynchronizer.tryCreate(
          acsSynchronizerId,
          indexStore.getOrCreateIndexForTesting(IndexedStringType.synchronizerId, acsDomainStr),
        )
        // Check we end up with the expected domain index. If we don't, then test isolation may get broken.
        assert(synchronizerId.index == domainIndex)
        new DbActiveContractStore(
          storage,
          synchronizerId,
          enableAdditionalConsistencyChecks = true,
          PrunableByTimeParameters.testingParams,
          indexStore,
          timeouts,
          loggerFactory,
        )(ec)
      },
      ec =>
        createDbContractStoreForTesting(
          storage,
          testedProtocolVersion,
          loggerFactory,
        )(ec),
    )

  }
}

private[db] object DbActiveContractStoreTest {

  /** Limit the range of domain indices that the ActiveContractStoreTest can use, to future-proof against interference
    * with the ContractStoreTest.
    * Currently, the ActiveContractStoreTest only needs to reserve the first index 1.
    */
  val maxDomainIndex: Int = 100
}

class ActiveContractStoreTestH2 extends DbActiveContractStoreTest with H2Test

class ActiveContractStoreTestPostgres extends DbActiveContractStoreTest with PostgresTest
