// Copyright (c) 2022 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.daml.platform.apiserver.services.admin

import com.daml.error.DamlContextualizedErrorLogger
import com.daml.error.definitions.LedgerApiErrors
import com.daml.ledger.api.domain.IdentityProviderConfig
import com.daml.ledger.api.v1.admin.{identity_provider_config_service => proto}
import com.daml.logging.{ContextualizedLogger, LoggingContext}
import com.daml.platform.IdentityProviderAwareAuthService
import com.daml.platform.api.grpc.GrpcApiService
import com.daml.platform.apiserver.services.admin.ApiIdentityProviderConfigService.toProto
import com.daml.platform.localstore.api
import com.daml.platform.localstore.api.IdentityProviderStore
import io.grpc.{ServerServiceDefinition, StatusRuntimeException}

import scala.concurrent.{ExecutionContext, Future}

class ApiIdentityProviderConfigService(
    identityProviderAwareAuthService: IdentityProviderAwareAuthService,
    identityProviderStore: IdentityProviderStore,
)(implicit
    executionContext: ExecutionContext,
    loggingContext: LoggingContext,
) extends proto.IdentityProviderConfigServiceGrpc.IdentityProviderConfigService
    with GrpcApiService {

  private implicit val logger: ContextualizedLogger = ContextualizedLogger.get(this.getClass)
  private implicit val contextualizedErrorLogger: DamlContextualizedErrorLogger =
    new DamlContextualizedErrorLogger(logger, loggingContext, None)

  import com.daml.platform.server.api.validation.FieldValidations._

  private def withValidation[A, B](validatedResult: Either[StatusRuntimeException, A])(
      f: A => Future[B]
  ): Future[B] =
    validatedResult.fold(Future.failed, Future.successful).flatMap(f)

  override def createIdentityProviderConfig(
      request: proto.CreateIdentityProviderConfigRequest
  ): Future[proto.CreateIdentityProviderConfigResponse] =
    withValidation {
      for {
        config <- requirePresence(request.identityProviderConfig, "identity_provider_config")
        identityProviderId <- requireIdentityProviderId(
          config.identityProviderId,
          "identity_provider_id",
        )
        jwksURL <- requireURL(config.jwksUrl, "jwks_uri")
        issuer <- requireNonEmptyString(config.issuer, "issuer")
      } yield IdentityProviderConfig(
        identityProviderId,
        config.isDeactivated,
        jwksURL,
        issuer,
      )
    } { config =>
      identityProviderStore
        .createIdentityProviderConfig(config)
        .flatMap(handleResult("creating identity_provider_config"))
        .map { config =>
          identityProviderAwareAuthService.addService(config)
          config
        }
        .map(config =>
          proto.CreateIdentityProviderConfigResponse(Some(toProto(config)))
        )
    }

  override def getIdentityProviderConfig(
      request: proto.GetIdentityProviderConfigRequest
  ): Future[proto.GetIdentityProviderConfigResponse] =
    withValidation(
      requireIdentityProviderId(request.identityProviderId, "identity_provider_id")
    )(identityProviderId =>
      identityProviderStore
        .getIdentityProviderConfig(identityProviderId)
        .flatMap(handleResult("getting identity_provider_config"))
        .map(cfg => proto.GetIdentityProviderConfigResponse(Some(toProto(cfg))))
    )

  override def updateIdentityProviderConfig(
      request: proto.UpdateIdentityProviderConfigRequest
  ): Future[proto.UpdateIdentityProviderConfigResponse] =
    Future.successful(proto.UpdateIdentityProviderConfigResponse())

  override def listIdentityProviderConfigs(
      request: proto.ListIdentityProviderConfigsRequest
  ): Future[proto.ListIdentityProviderConfigsResponse] =
    identityProviderStore
      .listIdentityProviderConfigs()
      .flatMap(handleResult("listing identity_provider_configs"))
      .map(result =>
        proto.ListIdentityProviderConfigsResponse(result.map(toProto).toSeq)
      )

  override def deleteIdentityProviderConfig(
      request: proto.DeleteIdentityProviderConfigRequest
  ): Future[proto.DeleteIdentityProviderConfigResponse] = {
    withValidation(
      requireIdentityProviderId(request.identityProviderId, "identity_provider_id")
    )(identityProviderId =>
      identityProviderStore
        .deleteIdentityProviderConfig(identityProviderId)
        .flatMap(handleResult("deleting identity_provider_config"))
        .map { _ =>
          identityProviderAwareAuthService.removeService(request.identityProviderId)
          proto.DeleteIdentityProviderConfigResponse()
        }
    )
  }

  private def handleResult[T](operation: String)(
      result: api.IdentityProviderStore.Result[T]
  ): Future[T] = result match {
    case Left(IdentityProviderStore.IdentityProviderConfigNotFound(id)) =>
      Future.failed(
        LedgerApiErrors.Admin.UserManagement.UserNotFound // TODO
          .Reject(operation, id.toString)
          .asGrpcError
      )
    case Left(IdentityProviderStore.IdentityProviderConfigByIssuerNotFound(id)) =>
      Future.failed(
        LedgerApiErrors.Admin.UserManagement.UserNotFound // TODO
          .Reject(operation, id.toString)
          .asGrpcError
      )
    case scala.util.Right(t) =>
      Future.successful(t)
  }

  override def close(): Unit = ()

  override def bindService(): ServerServiceDefinition =
    proto.IdentityProviderConfigServiceGrpc.bindService(this, executionContext)
}

object ApiIdentityProviderConfigService {
  private def toProto(
      identityProviderConfig: IdentityProviderConfig
  ): proto.IdentityProviderConfig =
    proto.IdentityProviderConfig(
      identityProviderId = identityProviderConfig.identityProviderId,
      isDeactivated = identityProviderConfig.isDeactivated,
      jwksUrl = identityProviderConfig.jwksURL.toString,
      issuer = identityProviderConfig.issuer,
    )

}