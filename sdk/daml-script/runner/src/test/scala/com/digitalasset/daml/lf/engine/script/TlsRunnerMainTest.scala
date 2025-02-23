// Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
// SPDX-License-Identifier: Apache-2.0

package com.digitalasset.daml.lf.engine.script

import com.daml.bazeltools.BazelRunfiles
import org.scalatest.Suite
import org.scalatest.freespec.AsyncFreeSpec

final class TlsRunnerMainTest extends AsyncFreeSpec with RunnerMainTestBaseCanton {
  self: Suite =>

  override protected lazy val tlsEnable: Boolean = true

  private val tlsArgs: Seq[String] = Seq(
    "--crt",
    BazelRunfiles.rlocation("test-common/test-certificates/server.crt"),
    "--pem",
    BazelRunfiles.rlocation("test-common/test-certificates/server.pem"),
    "--cacrt",
    BazelRunfiles.rlocation("test-common/test-certificates/ca.crt"),
    "--tls",
  )

  "TLS" - {
    "GRPC" - {
      "Succeeds with single run, no-upload" in
        testDamlScriptCanton(
          dars(0),
          Seq(
            "--ledger-host",
            "localhost",
            "--ledger-port",
            ports.head.toString,
            "--script-name",
            "TestScript:myScript",
          ) ++ tlsArgs,
          Right(Seq("Ran myScript")),
          Some(false),
        )
      "Succeeds with all run, no-upload" in
        testDamlScriptCanton(
          dars(1),
          Seq(
            "--ledger-host",
            "localhost",
            "--ledger-port",
            ports.head.toString,
            "--all",
          ) ++ tlsArgs,
          Right(
            Seq(
              "TestScript:myOtherScript SUCCESS",
              "TestScript:myScript SUCCESS",
            )
          ),
          Some(false),
        )
      "Succeeds with single run, upload flag" in
        testDamlScriptCanton(
          dars(3),
          Seq(
            "--ledger-host",
            "localhost",
            "--ledger-port",
            ports.head.toString,
            "--script-name",
            "TestScript:myScript",
            "--upload-dar=yes",
          ) ++ tlsArgs,
          Right(Seq("Ran myScript")),
          Some(true),
        )
      "Succeeds with single run, passing argument" in
        testDamlScriptCanton(
          dars(4),
          Seq(
            "--ledger-host",
            "localhost",
            "--ledger-port",
            ports.head.toString,
            "--script-name",
            "TestScript:inputScript",
            "--input-file",
            inputFile,
          ) ++ tlsArgs,
          Right(Seq("Got 5")),
        )
      "Fails without TLS args" in
        testDamlScriptCanton(
          dars(4),
          Seq(
            "--ledger-host",
            "localhost",
            "--ledger-port",
            ports.head.toString,
            "--script-name",
            "TestScript:myScript",
            "--upload-dar=yes",
          ),
          // On linux, we throw "UNAVAILABLE: Network closed for unknown reason"
          // On macOS, simply "UNAVAILABLE: io exception"
          // and on windows, ???
          // TODO: Make a consistent error for this with useful information.
          Left(Seq("UNAVAILABLE")),
        )
      "Succeeds using --participant-config" in
        withGrpcParticipantConfig { path =>
          testDamlScriptCanton(
            dars(4),
            Seq(
              "--participant-config",
              path.toString,
              "--script-name",
              "TestScript:myScript",
            ) ++ tlsArgs,
            Right(Seq("Ran myScript")),
          )
        }
    }
  }
}
