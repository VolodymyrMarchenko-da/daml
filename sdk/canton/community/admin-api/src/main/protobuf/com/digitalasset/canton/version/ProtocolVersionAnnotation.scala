// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.canton.version

object ProtocolVersionAnnotation {

  /** Type-level marker for whether a protocol version is stable */
  sealed trait Status

  /** Marker for alpha protocol versions */
  sealed trait Alpha extends Status

  /** Marker for stable protocol versions */
  sealed trait Stable extends Status

  /** Marker for beta protocol versions */
  sealed trait Beta extends Status
}

/** Marker trait for Protobuf messages generated by scalapb that are used in some stable protocol
  * versions
  *
  * Implements both [[com.digitalasset.canton.version.ProtocolVersionAnnotation.Stable]] and
  * [[com.digitalasset.canton.version.ProtocolVersionAnnotation.Alpha]] means that
  * [[StableProtoVersion]] messages can be used in stable and alpha protocol versions.
  */
trait StableProtoVersion
    extends ProtocolVersionAnnotation.Stable
    with ProtocolVersionAnnotation.Alpha

/** Marker trait for Protobuf messages generated by scalapb that are used only alpha protocol
  * versions
  */
trait AlphaProtoVersion extends ProtocolVersionAnnotation.Alpha

/** Marker trait for Protobuf messages generated by scalapb that are used only to persist data in
  * node storage. These messages are never exchanged as part of a protocol.
  */
trait StorageProtoVersion
