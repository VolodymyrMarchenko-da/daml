// Copyright (c) 2023 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.sandbox.auth

import com.daml.ledger.api.v1.testing.time_service.{SetTimeRequest, TimeServiceGrpc}

import scala.concurrent.Future

final class SetTimeAuthIT extends AdminServiceCallAuthTests with TimeAuth {

  override def serviceCallName: String = "TimeService#SetTime"

  override def serviceCall(context: ServiceCallContext): Future[Any] = {
    for {
      response <- loadTimeNow(context.token)
      _ <- stub(TimeServiceGrpc.stub(channel), context.token)
        .setTime(
          new SetTimeRequest(
            currentTime = response.get.currentTime,
            newTime = response.get.currentTime,
          )
        )
    } yield ()

  }

}