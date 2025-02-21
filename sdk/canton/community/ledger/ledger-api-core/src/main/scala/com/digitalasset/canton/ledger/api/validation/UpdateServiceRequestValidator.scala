// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.ledger.api.validation

import com.daml.error.ContextualizedErrorLogger
import com.daml.ledger.api.v2.update_service.{
  GetTransactionByIdRequest,
  GetTransactionByOffsetRequest,
  GetUpdatesRequest,
}
import com.digitalasset.canton.data.Offset
import com.digitalasset.canton.ledger.api.UpdateId
import com.digitalasset.canton.ledger.api.messages.update
import com.digitalasset.canton.ledger.api.validation.ValueValidator.*
import io.grpc.StatusRuntimeException

object UpdateServiceRequestValidator {
  type Result[X] = Either[StatusRuntimeException, X]

  import FieldValidator.*

  final case class PartialValidation(
      begin: Option[Offset],
      end: Option[Offset],
  )

  private def commonValidations(
      req: GetUpdatesRequest
  )(implicit contextualizedErrorLogger: ContextualizedErrorLogger): Result[PartialValidation] =
    for {
      begin <- ParticipantOffsetValidator
        .validateNonNegative(req.beginExclusive, "begin_exclusive")
      end <- ParticipantOffsetValidator
        .validateOptionalPositive(req.endInclusive, "end_inclusive")
    } yield PartialValidation(
      begin,
      end,
    )

  def validate(
      req: GetUpdatesRequest,
      ledgerEnd: Option[Offset],
  )(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Result[update.GetUpdatesRequest] =
    for {
      partial <- commonValidations(req)
      _ <- ParticipantOffsetValidator.offsetIsBeforeEnd(
        "Begin",
        partial.begin,
        ledgerEnd,
      )
      _ <- ParticipantOffsetValidator.offsetIsBeforeEnd(
        "End",
        partial.end,
        ledgerEnd,
      )
      updateFormat <- (req.filter, req.verbose, req.updateFormat) match {
        case (Some(_), _, Some(_)) =>
          Left(
            ValidationErrors.invalidArgument(
              s"Both filter/verbose and update_format is specified. Please use either backwards compatible arguments (filter and verbose) or update_format, but not both."
            )
          )
        case (Some(legacyFilter), legacyVerbose, None) =>
          FormatValidator.validateLegacyToUpdateFormat(legacyFilter, legacyVerbose)
        case (None, true, Some(_)) =>
          Left(
            ValidationErrors.invalidArgument(
              s"Both filter/verbose and update_format is specified. Please use either backwards compatible arguments (filter and verbose) or update_format, but not both."
            )
          )
        case (None, false, Some(updateFormat)) =>
          FormatValidator.validate(updateFormat)
        case (None, _, None) =>
          Left(
            ValidationErrors.invalidArgument(
              s"Either filter/verbose or update_format is required. Please use either backwards compatible arguments (filter and verbose) or update_format, but not both."
            )
          )
      }
    } yield {
      update.GetUpdatesRequest(
        partial.begin,
        partial.end,
        updateFormat,
      )
    }

  // TODO(#23504) cleanup
  def validateForTrees(
      req: GetUpdatesRequest,
      ledgerEnd: Option[Offset],
  )(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Result[update.GetUpdatesRequestForTrees] =
    for {
      _ <-
        if (req.updateFormat.nonEmpty)
          Left(
            ValidationErrors.invalidArgument(
              s"The event_format field must be unset for trees requests."
            )
          )
        else Right(())
      partial <- commonValidations(req)
      _ <- requireParties(req.getFilter.filtersByParty.keySet)
      _ <- ParticipantOffsetValidator.offsetIsBeforeEnd(
        "Begin",
        partial.begin,
        ledgerEnd,
      )
      _ <- ParticipantOffsetValidator.offsetIsBeforeEnd(
        "End",
        partial.end,
        ledgerEnd,
      )
      transactionFilter <- requirePresence(req.filter, "filter")
      eventFormat <- FormatValidator.validate(transactionFilter, req.verbose)
    } yield {
      update.GetUpdatesRequestForTrees(
        partial.begin,
        partial.end,
        eventFormat,
      )
    }

  def validateTransactionById(
      req: GetTransactionByIdRequest
  )(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Result[update.GetTransactionByIdRequest] =
    for {
      _ <- requireNonEmptyString(req.updateId, "update_id")
      trId <- requireLedgerString(req.updateId)
      _ <- requireNonEmpty(req.requestingParties, "requesting_parties")
      parties <- requireParties(req.requestingParties.toSet)
    } yield {
      update.GetTransactionByIdRequest(
        UpdateId(trId),
        parties,
      )
    }

  def validateTransactionByOffset(
      req: GetTransactionByOffsetRequest
  )(implicit
      contextualizedErrorLogger: ContextualizedErrorLogger
  ): Result[update.GetTransactionByOffsetRequest] =
    for {
      offset <- ParticipantOffsetValidator.validatePositive(req.offset, "offset")
      _ <- requireNonEmpty(req.requestingParties, "requesting_parties")
      parties <- requireParties(req.requestingParties.toSet)
    } yield {
      update.GetTransactionByOffsetRequest(
        offset,
        parties,
      )
    }
}
