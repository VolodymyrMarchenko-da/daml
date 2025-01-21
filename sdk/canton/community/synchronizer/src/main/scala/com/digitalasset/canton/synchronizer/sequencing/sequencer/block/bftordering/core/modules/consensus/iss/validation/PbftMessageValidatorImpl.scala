// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.validation

import com.daml.metrics.api.MetricsContext
import com.digitalasset.canton.synchronizer.metrics.BftOrderingMetrics
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.core.modules.consensus.iss.IssConsensusModuleMetrics.emitNonCompliance
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.framework.data.NumberIdentifiers.{
  EpochNumber,
  ViewNumber,
}
import com.digitalasset.canton.synchronizer.sequencing.sequencer.block.bftordering.framework.modules.ConsensusSegment.ConsensusMessage.PrePrepare

trait PbftMessageValidator {
  def validatePrePrepare(prePrepare: PrePrepare, firstInSegment: Boolean): Either[String, Unit]
}

final class PbftMessageValidatorImpl(
    epoch: EpochNumber,
    view: ViewNumber,
    metrics: BftOrderingMetrics,
)(implicit mc: MetricsContext)
    extends PbftMessageValidator {

  def validatePrePrepare(prePrepare: PrePrepare, firstInSegment: Boolean): Either[String, Unit] = {
    // TODO(#17108): verify PrePrepare is sound in terms of ProofsOfAvailability
    // TODO(#23335): further validate canonical commits
    val canonicalCommitSet = prePrepare.canonicalCommitSet.sortedCommits
    // A canonical commit set for a non-empty block should only be empty for the first block after state transfer,
    //  but it's hard to fully enforce.
    val canonicalCommitSetCanBeEmpty = prePrepare.block.proofs.isEmpty || firstInSegment
    Either.cond(
      canonicalCommitSet.nonEmpty || canonicalCommitSetCanBeEmpty,
      (), {
        emitNonCompliance(metrics)(
          prePrepare.from,
          epoch,
          view,
          prePrepare.blockMetadata.blockNumber,
          metrics.security.noncompliant.labels.violationType.values.ConsensusInvalidMessage,
        )

        s"Canonical commit set is empty for block ${prePrepare.blockMetadata} with ${prePrepare.block.proofs.size} " +
          "proofs of availability but it can only be empty for empty blocks or first blocks in segments"
      },
    )
  }
}
