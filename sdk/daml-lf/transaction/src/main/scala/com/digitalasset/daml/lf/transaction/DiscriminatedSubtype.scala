// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.daml.lf.transaction

sealed abstract class DiscriminatedSubtype[X] {
  type T <: X
  def apply(x: X): T
  def subst[F[_]](fx: F[X]): F[T]
}

object DiscriminatedSubtype {
  private[transaction] def apply[X]: DiscriminatedSubtype[X] = new DiscriminatedSubtype[X] {
    override type T = X
    override def apply(x: X): T = x
    override def subst[F[_]](fx: F[X]): F[T] = fx
  }
}
