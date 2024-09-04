// Copyright (c) 2024 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.daml.lf.speedy.wasm

import com.dylibso.chicory.runtime.{HostFunction => WasmHostFunction}

object PureWasmHostFunctions {
  import WasmUtils._
  import WasmRunnerHostFunctions._

  val createContractFunc: WasmHostFunction =
    wasmFunction("createContract", 2, WasmValueResultType) { _ =>
      throw new RuntimeException(
        "Host functions can not be called from pure WASM exported functions: createContract"
      )
    }

  val fetchContractArgFunc: WasmHostFunction =
    wasmFunction("fetchContractArg", 3, WasmValueResultType) { _ =>
      throw new RuntimeException(
        "Host functions can not be called from pure WASM exported functions: fetchContractArg"
      )
    }

  val exerciseChoiceFunc: WasmHostFunction =
    wasmFunction("exerciseChoice", 5, WasmValueResultType) { _ =>
      throw new RuntimeException(
        "Host functions can not be called from pure WASM exported functions: exerciseChoice"
      )
    }
}