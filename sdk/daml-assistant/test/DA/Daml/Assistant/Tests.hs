-- Copyright (c) 2025 Digital Asset (Switzerland) GmbH and/or its affiliates. All rights reserved.
-- SPDX-License-Identifier: Apache-2.0

module DA.Daml.Assistant.Tests
    ( module DA.Daml.Assistant.Tests
    ) where

import DA.Daml.Assistant.Env
import DA.Daml.Assistant.Install
import DA.Daml.Assistant.Cache (UseCache (DontUseCache))
import DA.Daml.Assistant.Types
import DA.Daml.Assistant.Util
import DA.Daml.Assistant.Version as V hiding (UseCache(..))
import DA.Daml.Project.Consts hiding (getDamlPath, getProjectPath)
import DA.Daml.Project.Config
import System.Directory
import System.Environment.Blank
import System.FilePath
import System.Info.Extra (isWindows)
import System.IO.Temp
import System.IO.Extra
import Data.List.Extra
import DA.Test.Util
import qualified Test.Tasty as Tasty
import qualified Test.Tasty.HUnit as Tasty
import Test.Tasty.HUnit ((@?=))
import qualified Test.Tasty.QuickCheck as Tasty
import qualified Data.Text as T
import Test.Tasty.QuickCheck ((==>))
import Data.Bifunctor (second)
import qualified Data.Map as Map
import Data.Maybe
import qualified Data.Yaml as Y
import Control.Exception (catch, displayException)
import Control.Monad
import Conduit
import qualified Data.Conduit.Zlib as Zlib
import qualified Data.Conduit.Tar as Tar
import qualified Data.SemVer as V

-- unix specific
import System.PosixCompat.Files (createSymbolicLink)

main :: IO ()
main = do
    setEnv "TASTY_NUM_THREADS" "1" True -- we need this because we use withEnv in our tests
    Tasty.defaultMain $ Tasty.testGroup "DA.Daml.Assistant"
        [ testAscendants
        , testGetDamlPath
        , testGetProjectPath
        , testGetSdk
        , testGetDispatchEnv
        , testInstall
        , testEnvironmentVariableInterpolation
        ]

assertError :: Text -> Text -> IO a -> IO ()
assertError ctxPattern msgPattern action = do
    result <- tryAssistant action
    case result of
        Left AssistantError{..} -> do
            Tasty.assertBool ("Error context pattern does not match error. Expected: " <> show ctxPattern <> ". Got: " <> show errContext <> ".") (ctxPattern `T.isInfixOf` fromMaybe "" errContext)
            Tasty.assertBool ("Error message pattern does not match error. Expected: " <> show msgPattern <> ". Got: " <> show errMessage <> ".") (msgPattern `T.isInfixOf` fromMaybe "" errMessage)
        Right _ ->
            Tasty.assertFailure "Expected assistant error."

testGetDamlPath :: Tasty.TestTree
testGetDamlPath = Tasty.testGroup "DA.Daml.Assistant.Env.getDamlPath"
    [ Tasty.testCase "getDamlPath returns DAML_HOME" $ do
            withSystemTempDirectory "test-getDamlPath" $ \expected -> do
                DamlPath got <- withEnv [(damlPathEnvVar, Just expected)] getDamlPath
                Tasty.assertEqual "daml home path" expected got
    , Tasty.testCase "getDamlPath returns DAML_HOME (made absolute)" $ do
            withSystemTempDirectory "test-getDamlPath" $ \dir -> do
                let expected = dir </> "daml"
                setCurrentDirectory dir
                createDirectory expected
                DamlPath got <- withEnv [(damlPathEnvVar, Just "daml")] getDamlPath
                Tasty.assertEqual "daml home path" expected got
    , if isWindows
        then testGetDamlPathWindows
        else testGetDamlPathPosix
    ]

testGetDamlPathWindows :: Tasty.TestTree
testGetDamlPathWindows = Tasty.testGroup "windows-specific tests"
    [ Tasty.testCase "getDamlPath gets app user data directory by default" $ do
             DamlPath got <- withEnv [ (damlPathEnvVar, Nothing)] getDamlPath
             let expectedSuffix = "\\AppData\\Roaming\\daml"
             let failureMsg = "daml home path - " ++ show got ++ " does not end with: " ++ show expectedSuffix
             Tasty.assertBool failureMsg $ expectedSuffix `isSuffixOf` got
     ]

testGetDamlPathPosix :: Tasty.TestTree
testGetDamlPathPosix = Tasty.testGroup "posix-specific tests"
    [ Tasty.testCase "getDamlPath gets app user data directory by default" $ do
            withSystemTempDirectory "test-getDamlPath" $ \base -> do
                 let expected = base </> ".daml"
                 createDirectory expected
                 DamlPath got <- withEnv [ ("HOME", Just base)
                                         , (damlPathEnvVar, Nothing)
                                         ] getDamlPath
                 Tasty.assertEqual "daml home path" expected got
    ]

testGetProjectPath :: Tasty.TestTree
testGetProjectPath = Tasty.testGroup "DA.Daml.Assistant.Env.getProjectPath"
    [ Tasty.testCase "getProjectPath returns environment variable" $ do
        withSystemTempDirectory "test-getProjectPath" $ \dir -> do
            let expected = dir </> "project"
            setCurrentDirectory dir
            createDirectory expected
            Just got <- withEnv [(projectPathEnvVar, Just expected)] getProjectPath'
            Tasty.assertEqual "project path" (ProjectPath expected) got
            return ()

    , Tasty.testCase "getProjectPath returns environment variable (made absolute)" $ do
        withSystemTempDirectory "test-getProjectPath" $ \dir -> do
            let expected = dir </> "project"
            setCurrentDirectory dir
            createDirectory expected
            Just got <- withEnv [(projectPathEnvVar, Just "project")] getProjectPath'
            Tasty.assertEqual "project path" (ProjectPath expected) got
            return ()

    , Tasty.testCase "getProjectPath returns nothing" $ do
        -- This test assumes there's no daml.yaml above the temp directory.
        -- ... this might be an ok assumption, but maybe getProjectPath
        -- should also check that the project path is owned by the user,
        -- or something super fancy like that.
        withSystemTempDirectory "test-getProjectPath" $ \dir -> do
            setCurrentDirectory dir
            Nothing <- withEnv [(projectPathEnvVar, Nothing)] getProjectPath'
            return ()

    , Tasty.testCase "getProjectPath returns current directory" $ do
        withSystemTempDirectory "test-getProjectPath" $ \dir -> do
            writeFileUTF8 (dir </> projectConfigName) ""
            setCurrentDirectory dir
            Just path <- withEnv [(projectPathEnvVar, Nothing)] getProjectPath'
            Tasty.assertEqual "project path" (ProjectPath dir) path

    , Tasty.testCase "getProjectPath returns parent directory" $ do
        withSystemTempDirectory "test-getProjectPath" $ \dir -> do
            createDirectory (dir </> "foo")
            writeFileUTF8 (dir </> projectConfigName) ""
            setCurrentDirectory (dir </> "foo")
            Just path <- withEnv [(projectPathEnvVar, Nothing)] getProjectPath'
            Tasty.assertEqual "project path" (ProjectPath dir) path

    , Tasty.testCase "getProjectPath returns grandparent directory" $ do
        withSystemTempDirectory "test-getProjectPath" $ \dir -> do
            createDirectoryIfMissing True (dir </> "foo" </> "bar")
            writeFileUTF8 (dir </> projectConfigName) ""
            setCurrentDirectory (dir </> "foo" </> "bar")
            Just path <- withEnv [(projectPathEnvVar, Nothing)] getProjectPath'
            Tasty.assertEqual "project path" (ProjectPath dir) path

    , Tasty.testCase "getProjectPath prefers parent over grandparent" $ do
        withSystemTempDirectory "test-getProjectPath" $ \dir -> do
            createDirectoryIfMissing True (dir </> "foo" </> "bar")
            writeFileUTF8 (dir </> projectConfigName) ""
            writeFileUTF8 (dir </> "foo" </> projectConfigName) ""
            setCurrentDirectory (dir </> "foo" </> "bar")
            Just path <- withEnv [(projectPathEnvVar, Nothing)] getProjectPath'
            Tasty.assertEqual "project path" (ProjectPath (dir </> "foo")) path

    ]
    where
        getProjectPath' = getProjectPath (LookForProjectPath True)

testGetSdk :: Tasty.TestTree
testGetSdk = Tasty.testGroup "DA.Daml.Assistant.Env.getSdk"
    [ Tasty.testCase "getSdk returns DAML_SDK_VERSION and DAML_SDK" $ do
        withSystemTempDirectory "test-getSdk" $ \base -> do
            let damlPath = DamlPath (base </> "daml")
                cachePath = CachePath (base </> "cache")
                projectPath = Nothing
                expected1 = "10.10.10"
                expected2 = base </> "sdk"

            createDirectoryIfMissing True (base </> "cache")
            writeFileUTF8 (unwrapCachePath cachePath </> "versions.txt") expected1
            createDirectory expected2
            (Just got1, Just (SdkPath got2)) <-
                withEnv [ (sdkVersionEnvVar, Just expected1)
                        , (sdkPathEnvVar, Just expected2)
                        ] (getSdk (mkUseCache cachePath damlPath) damlPath projectPath)
            Tasty.assertEqual "sdk version" expected1 (versionToString got1)
            Tasty.assertEqual "sdk path" expected2 got2

    , Tasty.testCase "getSdk determines DAML_SDK from DAML_SDK_VERSION" $ do
        withSystemTempDirectory "test-getSdk" $ \base -> do
            let damlPath = DamlPath (base </> "daml")
                cachePath = CachePath (base </> "cache")
                projectPath = Nothing
                expected1 = "0.12.5-version"
                expected2 = base </> "daml" </> "sdk" </> expected1

            createDirectoryIfMissing True (base </> "daml" </> "sdk")
            createDirectoryIfMissing True (base </> "cache")
            writeFileUTF8 (unwrapCachePath cachePath </> "versions.txt") expected1
            createDirectory expected2
            writeFileUTF8 (expected2 </> sdkConfigName) ("version: " <> expected1 <> "\n")
            (Just got1, Just (SdkPath got2)) <-
                withEnv [ (sdkVersionEnvVar, Just expected1)
                        , (sdkPathEnvVar, Nothing)
                        ] (getSdk (mkUseCache cachePath damlPath) damlPath projectPath)
            Tasty.assertEqual "sdk version" expected1 (versionToString got1)
            Tasty.assertEqual "sdk path" expected2 got2

    , Tasty.testCase "getSdk determines DAML_SDK_VERSION from DAML_SDK" $ do
        withSystemTempDirectory "test-getSdk" $ \base -> do
            let damlPath = DamlPath (base </> "daml")
                cachePath = CachePath (base </> "cache")
                projectPath = Nothing
                expected1 = "0.3.4"
                expected2 = base </> "sdk2"

            createDirectoryIfMissing True (base </> "cache")
            writeFileUTF8 (unwrapCachePath cachePath </> "versions.txt") expected1
            createDirectory expected2
            writeFileUTF8 (expected2 </> sdkConfigName) ("version: " <> expected1 <> "\n")
            (Just got1, Just (SdkPath got2)) <-
                withEnv [ (sdkVersionEnvVar, Nothing)
                        , (sdkPathEnvVar, Just expected2)
                        ] (getSdk (mkUseCache cachePath damlPath) damlPath projectPath)
            Tasty.assertEqual "sdk version" expected1 (versionToString got1)
            Tasty.assertEqual "sdk path" expected2 got2

    , Tasty.testCase "getSdk determines DAML_SDK and DAML_SDK_VERSION from project config" $ do
        withSystemTempDirectory "test-getSdk" $ \base -> do
            let damlPath = DamlPath (base </> "daml")
                cachePath = CachePath (base </> "cache")
                projectPath = Just $ ProjectPath (base </> "project")
                expected1 = "10.10.2-version.af29bef"
                expected2 = base </> "daml" </> "sdk" </> expected1

            createDirectoryIfMissing True (base </> "daml" </> "sdk")
            createDirectoryIfMissing True (base </> "cache")
            writeFileUTF8 (unwrapCachePath cachePath </> "versions.txt") expected1
            createDirectory (base </> "project")
            writeFileUTF8 (base </> "project" </> projectConfigName)
                ("sdk-version: " <> expected1)
            createDirectory expected2
            (Just got1, Just (SdkPath got2)) <-
                withEnv [ (sdkVersionEnvVar, Nothing)
                        , (sdkPathEnvVar, Nothing)
                        ] (getSdk (mkUseCache cachePath damlPath) damlPath projectPath)
            Tasty.assertEqual "sdk version" expected1 (versionToString got1)
            Tasty.assertEqual "sdk path" expected2 got2

    , Tasty.testCase "getSdk: DAML_SDK overrides project config version" $ do
        withSystemTempDirectory "test-getSdk" $ \base -> do
            let damlPath = DamlPath (base </> "daml")
                cachePath = CachePath (base </> "cache")
                projectPath = Just $ ProjectPath (base </> "project")
                expected1 = "0.9.8-ham"
                expected2 = base </> "sdk3"
                projVers = "5.2.1"

            createDirectoryIfMissing True (base </> "daml" </> "sdk" </> projVers)
            createDirectoryIfMissing True (base </> "cache")
            writeFileUTF8 (unwrapCachePath cachePath </> "versions.txt") expected1
            createDirectory (base </> "project")
            writeFileUTF8 (base </> "project" </> projectConfigName)
                ("project:\n  sdk-version: " <> projVers)
            createDirectory expected2
            writeFileUTF8 (expected2 </> sdkConfigName) ("version: " <> expected1 <> "\n")
            (Just got1, Just (SdkPath got2)) <-
                withEnv [ (sdkVersionEnvVar, Nothing)
                        , (sdkPathEnvVar, Just expected2)
                        ] (getSdk (mkUseCache cachePath damlPath) damlPath projectPath)
            Tasty.assertEqual "sdk version" expected1 (versionToString got1)
            Tasty.assertEqual "sdk path" expected2 got2

    , Tasty.testCase "getSdk: DAML_SDK_VERSION overrides project config version" $ do
        withSystemTempDirectory "test-getSdk" $ \base -> do
            let damlPath = DamlPath (base </> "daml")
                cachePath = CachePath (base </> "cache")
                projectPath = Just $ ProjectPath (base </> "project")
                expected1 = "0.0.0"
                expected2 = base </> "daml" </> "sdk" </> expected1
                projVers = "0.0.1"

            createDirectoryIfMissing True (base </> "daml" </> "sdk" </> projVers)
            createDirectoryIfMissing True (base </> "cache")
            writeFileUTF8 (unwrapCachePath cachePath </> "versions.txt") expected1
            createDirectory (base </> "project")
            writeFileUTF8 (base </> "project" </> projectConfigName)
                ("project:\n  sdk-version: " <> projVers)
            createDirectory expected2
            (Just got1, Just (SdkPath got2)) <-
                withEnv [ (sdkVersionEnvVar, Just expected1)
                        , (sdkPathEnvVar, Nothing)
                        ] (getSdk (mkUseCache cachePath damlPath) damlPath projectPath)
            Tasty.assertEqual "sdk version" expected1 (versionToString got1)
            Tasty.assertEqual "sdk path" expected2 got2

    , Tasty.testCase "getSdk: Returns Nothings if .daml/sdk is missing." $ do
        withSystemTempDirectory "test-getSdk" $ \base -> do
            let damlPath = DamlPath (base </> "daml")
                cachePath = CachePath (base </> "cache")
                projPath = Nothing
            createDirectoryIfMissing True (base </> "daml")
            createDirectoryIfMissing True (base </> "cache")
            (Nothing, Nothing) <- withEnv
                [ (sdkVersionEnvVar, Nothing)
                , (sdkPathEnvVar, Nothing)
                ] (getSdk (mkUseCache cachePath damlPath) damlPath projPath)
            pure ()
    ]

testGetDispatchEnv :: Tasty.TestTree
testGetDispatchEnv = Tasty.testGroup "DA.Daml.Assistant.Env.getDispatchEnv"
    [ Tasty.testCase "getDispatchEnv should be idempotent" $ do
        withSystemTempDirectory "test-getDispatchEnv" $ \base -> do
            version <- requiredE "testGetDispatchEnv: expected a valid version" $ unsafeParseOldReleaseVersion "1.0.1"
            let unresolvedVersion = unresolvedVersionFromReleaseVersion version
            let cachePath = CachePath (base </> ".cache")
            createDirectoryIfMissing True (unwrapCachePath cachePath)
            writeFileUTF8 (unwrapCachePath cachePath </> "versions.txt") "1.0.1"
            let denv = Env
                    { envDamlPath = DamlPath (base </> ".daml")
                    , envCachePath = cachePath
                    , envDamlAssistantPath = DamlAssistantPath (base </> ".daml" </> "bin" </> "strange-daml")
                    , envDamlAssistantSdkVersion = Just $ DamlAssistantSdkVersion version
                    , envSdkVersion = Just unresolvedVersion
                    , envFreshStableSdkVersionForCheck = pure (Just version)
                    , envSdkPath = Just $ SdkPath (base </> "sdk")
                    , envProjectPath = Just $ ProjectPath (base </> "proj")
                    }
            env1 <- withEnv [] (getDispatchEnv denv)
            env2 <- withEnv (fmap (fmap Just) env1) (getDispatchEnv denv)
            Tasty.assertEqual "dispatch envs" env1 env2

    , Tasty.testCase "getDispatchEnv should override getDamlEnv" $ do
        withSystemTempDirectory "test-getDispatchEnv" $ \base -> do
            version <- requiredE "testGetDispatchEnv: expected a valid version" $ unsafeParseOldReleaseVersion "1.0.1"
            let unresolvedVersion = unresolvedVersionFromReleaseVersion version
            let cachePath = CachePath (base </> ".cache")
            createDirectoryIfMissing True (unwrapCachePath cachePath)
            writeFileUTF8 (unwrapCachePath cachePath </> "versions.txt") "1.0.1"
            let denv1 = Env
                    { envDamlPath = DamlPath (base </> ".daml")
                    , envCachePath = cachePath
                    , envDamlAssistantPath = DamlAssistantPath (base </> ".daml" </> "bin" </> "strange-daml")
                    , envDamlAssistantSdkVersion = Just $ DamlAssistantSdkVersion version
                    , envSdkVersion = Just unresolvedVersion
                    , envFreshStableSdkVersionForCheck = pure (Just version)
                    , envSdkPath = Just $ SdkPath (base </> "sdk")
                    , envProjectPath = Just $ ProjectPath (base </> "proj")
                    }
            env <- withEnv [] (getDispatchEnv denv1)
            denv2 <- withEnv (fmap (fmap Just) env) (getDamlEnv' =<< getDamlPath)
            forcedDenv1 <- forceEnv denv1
            forcedDenv2 <- forceEnv denv2
            Tasty.assertEqual "daml envs" forcedDenv1 forcedDenv2

    , Tasty.testCase "getDispatchEnv should override getDamlEnv (2)" $ do
        withSystemTempDirectory "test-getDispatchEnv" $ \base -> do
            let denv1 = Env
                    { envDamlPath = DamlPath (base </> ".daml")
                    , envCachePath = CachePath (base </> ".cache")
                    , envDamlAssistantPath = DamlAssistantPath (base </> ".daml" </> "bin" </> "strange-daml")
                    , envDamlAssistantSdkVersion = Nothing
                    , envSdkVersion = Nothing
                    , envFreshStableSdkVersionForCheck = pure Nothing
                    , envSdkPath = Nothing
                    , envProjectPath = Nothing
                    }
            env <- withEnv [] (getDispatchEnv denv1)
            denv2 <- withEnv (fmap (fmap Just) env) (getDamlEnv' =<< getDamlPath)
            forcedDenv1 <- forceEnv denv1
            forcedDenv2 <- forceEnv denv2
            Tasty.assertEqual "daml envs" forcedDenv1 forcedDenv2
    ]
    where
        getDamlEnv' x = getDamlEnv x (LookForProjectPath True)

testAscendants :: Tasty.TestTree
testAscendants = Tasty.testGroup "DA.Daml.Project.Util.ascendants"
    [ Tasty.testCase "unit tests" $ do
        Tasty.assertEqual "empty path" ["."] (ascendants "")
        Tasty.assertEqual "curdir path" ["."] (ascendants ".")
        Tasty.assertEqual "root path" ["/"] (ascendants "/")
        Tasty.assertEqual "home path" ["~"] (ascendants "~")
        Tasty.assertEqual "foo/bar" ["foo/bar", "foo", "."] (ascendants "foo/bar")
        Tasty.assertEqual "foo/bar/" ["foo/bar/", "foo", "."] (ascendants "foo/bar/")
        Tasty.assertEqual "./foo/bar" ["./foo/bar", "./foo", "."] (ascendants "./foo/bar")
        Tasty.assertEqual "../foo/bar" ["../foo/bar", "../foo", ".."] (ascendants "../foo/bar")
        Tasty.assertEqual "~/foo/bar" ["~/foo/bar", "~/foo", "~"] (ascendants "~/foo/bar")
        Tasty.assertEqual "/foo/bar/baz" ["/foo/bar/baz", "/foo/bar", "/foo", "/"]
            (ascendants "/foo/bar/baz")
    , Tasty.testProperty "ascendants is nonempty"
        (\p -> notNull (ascendants p))
    , Tasty.testProperty "head . ascendants == id"
        (\p -> notNull p ==> head (ascendants p) == p)
    , Tasty.testProperty "head . ascendants == id (2)"
        (\p1 p2 -> let p = p1 </> p2 in notNull p1 && notNull p2 && isRelative p2 ==>
                   head (ascendants p) == p)
    , Tasty.testProperty "tail . ascendants == ascendants . takeDirectory"
        (\p1 p2 -> let p = dropTrailingPathSeparator (p1 </> p2)
                   in notNull p1 && notNull p2 && p1 </> p2 /= p2 ==>
                      -- We use `p1 </> p2 /= p2` instead of `isRelative p2`
                      -- because, on Windows, `isRelative "\\foo" == True`,
                      -- while `x </> "\\foo" = "\\foo"`.
                      tail (ascendants p) == ascendants (takeDirectory p))
    ]

testInstall :: Tasty.TestTree
testInstall = Tasty.testGroup "DA.Daml.Assistant.Install"
    [ Tasty.testCase "initial install a tarball" $ do
        withSystemTempDirectory "test-install" $ \ base -> do
            let damlPath = DamlPath (base </> "daml")
                options = InstallOptions
                    { iTargetM = Just (RawInstallTarget "source.tar.gz")
                    , iSnapshots = False
                    , iAssistant = InstallAssistant Yes
                    , iQuiet = QuietInstall True
                    , iForce = ForceInstall False
                    , iSetPath = SetPath No
                    , iBashCompletions = BashCompletions No
                    , iZshCompletions = ZshCompletions No
                    , iInstallWithInternalVersion = InstallWithInternalVersion False
                    , iInstallWithCustomVersion = InstallWithCustomVersion Nothing
                    }

            setCurrentDirectory base
            createDirectoryIfMissing True "source"
            createDirectoryIfMissing True ("source" </> "daml")
            writeFileUTF8 ("source" </> sdkConfigName) "version: 0.0.0-test"
            -- daml / daml.exe "binary" for --install-assistant=yes
            writeFileUTF8 ("source" </> "daml" </> if isWindows then "daml.exe" else "daml") ""

            runConduitRes $
                yield "source"
                .| void Tar.tarFilePath
                .| Zlib.gzip
                .| sinkFile "source.tar.gz"

            install options damlPath DontUseCache Nothing Nothing
    , if isWindows
        then testInstallWindows
        else testInstallUnix
    , Tasty.testCase "generate correct download URLs for any os and architecture" $ do
        let fromText v =
              case V.fromText v of
                Left err -> Tasty.assertFailure ("Could not parse '" <> T.unpack v <> "' as a version: " <> err)
                Right v -> pure v
            assertVersion os arch version expected =
              Tasty.assertEqual
                "download strings don't match"
                (V.renderVersionLocationOverrideOsArch version "" os arch)
                expected
            assertSplitVersion os arch rawReleaseVersion rawInternalVersion expected = do
              releaseVersion <- fromText rawReleaseVersion
              internalVersion <- fromText rawInternalVersion
              assertVersion os arch (SplitReleaseVersion releaseVersion internalVersion) expected
            assertOldVersion os arch rawOldVersion expected = do
              oldVersion <- fromText rawOldVersion
              assertVersion os arch (OldReleaseVersion oldVersion) expected


        -- Unix
        assertSplitVersion "linux" "x86_64" "2.9.4" "2.9.4"
          "/v2.9.4/daml-sdk-2.9.4-linux.tar.gz"
        assertSplitVersion "linux" "x86_64" "2.10.0-snapshot.20240703.0" "2.9.0-snapshot.20240625.12857.0.v2aea6caf"
          "/v2.10.0-snapshot.20240703.0/daml-sdk-2.9.0-snapshot.20240625.12857.0.v2aea6caf-linux.tar.gz"
        assertOldVersion "linux" "x86_64" "2.6.5"
          "/v2.6.5/daml-sdk-2.6.5-linux.tar.gz"
        assertSplitVersion "linux" "aarch64" "3.2.0-snapshot.20240828.0" "3.2.0-snapshot.20240819.13232.0.vee0b4864"
          "/v3.2.0-snapshot.20240828.0/daml-sdk-3.2.0-snapshot.20240819.13232.0.vee0b4864-linux-aarch64.tar.gz"
        assertSplitVersion "linux" "x86_64" "3.2.0-snapshot.20240828.0" "3.2.0-snapshot.20240819.13232.0.vee0b4864"
          "/v3.2.0-snapshot.20240828.0/daml-sdk-3.2.0-snapshot.20240819.13232.0.vee0b4864-linux-x86_64.tar.gz"

        -- Windows
        assertSplitVersion "mingw32" "x86_64" "2.9.4" "2.9.4"
          "/v2.9.4/daml-sdk-2.9.4-windows.tar.gz"
        assertSplitVersion "mingw32" "x86_64" "2.10.0-snapshot.20240703.0" "2.9.0-snapshot.20240625.12857.0.v2aea6caf"
          "/v2.10.0-snapshot.20240703.0/daml-sdk-2.9.0-snapshot.20240625.12857.0.v2aea6caf-windows.tar.gz"
        assertOldVersion "mingw32" "x86_64" "2.6.5"
          "/v2.6.5/daml-sdk-2.6.5-windows.tar.gz"
        assertSplitVersion "mingw32" "aarch64" "3.2.0-snapshot.20240828.0" "3.2.0-snapshot.20240819.13232.0.vee0b4864"
          "/v3.2.0-snapshot.20240828.0/daml-sdk-3.2.0-snapshot.20240819.13232.0.vee0b4864-windows-aarch64.tar.gz"
        assertSplitVersion "mingw32" "x86_64" "3.2.0-snapshot.20240828.0" "3.2.0-snapshot.20240819.13232.0.vee0b4864"
          "/v3.2.0-snapshot.20240828.0/daml-sdk-3.2.0-snapshot.20240819.13232.0.vee0b4864-windows-x86_64.tar.gz"

        -- MacOS
        assertSplitVersion "darwin" "x86_64" "2.9.4" "2.9.4"
          "/v2.9.4/daml-sdk-2.9.4-macos.tar.gz"
        assertSplitVersion "darwin" "x86_64" "2.10.0-snapshot.20240703.0" "2.9.0-snapshot.20240625.12857.0.v2aea6caf"
          "/v2.10.0-snapshot.20240703.0/daml-sdk-2.9.0-snapshot.20240625.12857.0.v2aea6caf-macos.tar.gz"
        assertOldVersion "darwin" "x86_64" "2.6.5"
          "/v2.6.5/daml-sdk-2.6.5-macos.tar.gz"
        assertSplitVersion "darwin" "aarch64" "3.2.0-snapshot.20240828.0" "3.2.0-snapshot.20240819.13232.0.vee0b4864"
          "/v3.2.0-snapshot.20240828.0/daml-sdk-3.2.0-snapshot.20240819.13232.0.vee0b4864-macos-aarch64.tar.gz"
        assertSplitVersion "darwin" "x86_64" "3.2.0-snapshot.20240828.0" "3.2.0-snapshot.20240819.13232.0.vee0b4864"
          "/v3.2.0-snapshot.20240828.0/daml-sdk-3.2.0-snapshot.20240819.13232.0.vee0b4864-macos-x86_64.tar.gz"
    ]

testInstallUnix :: Tasty.TestTree
testInstallUnix = Tasty.testGroup "unix-specific tests"
    [ Tasty.testCase "initial install a tarball from symlink" $ do
              withSystemTempDirectory "test-install" $ \ base -> do
                  let damlPath = DamlPath (base </> "daml")
                      options = InstallOptions
                          { iTargetM = Just (RawInstallTarget "source.tar.gz")
                          , iSnapshots = False
                          , iAssistant = InstallAssistant Yes
                          , iQuiet = QuietInstall True
                          , iForce = ForceInstall False
                          , iSetPath = SetPath No
                          , iBashCompletions = BashCompletions No
                          , iZshCompletions = ZshCompletions No
                          , iInstallWithInternalVersion = InstallWithInternalVersion False
                          , iInstallWithCustomVersion = InstallWithCustomVersion Nothing
                          }

                  setCurrentDirectory base
                  createDirectoryIfMissing True "source"
                  createDirectoryIfMissing True ("source" </> "daml")
                  writeFileUTF8 ("source" </> sdkConfigName) "version: 0.0.0-test"
                  writeFileUTF8 ("source" </> "daml" </> "daml") "" -- daml "binary" for --install-assistant=yes
                  createSymbolicLink ("daml" </> "daml") ("source" </> "daml-link")
                      -- check if symbolic links are handled correctly

                  runConduitRes $
                      yield "source"
                      .| void Tar.tarFilePath
                      .| Zlib.gzip
                      .| sinkFile "source.tar.gz"

                  install options damlPath DontUseCache Nothing Nothing,

      Tasty.testCase "reject an absolute symlink in a tarball" $ do
        withSystemTempDirectory "test-install" $ \ base -> do
            let damlPath = DamlPath (base </> "daml")
                options = InstallOptions
                    { iTargetM = Just (RawInstallTarget "source.tar.gz")
                    , iSnapshots = False
                    , iAssistant = InstallAssistant No
                    , iQuiet = QuietInstall True
                    , iForce = ForceInstall False
                    , iSetPath = SetPath No
                    , iBashCompletions = BashCompletions No
                    , iZshCompletions = ZshCompletions No
                    , iInstallWithInternalVersion = InstallWithInternalVersion False
                    , iInstallWithCustomVersion = InstallWithCustomVersion Nothing
                    }

            setCurrentDirectory base
            createDirectoryIfMissing True "source"
            writeFileUTF8 ("source" </> sdkConfigName) "version: 0.0.0-test"
            createSymbolicLink (base </> "daml") ("source" </> "daml-link")
                -- absolute symlink

            runConduitRes $
                yield "source"
                .| void Tar.tarFilePath
                .| Zlib.gzip
                .| sinkFile "source.tar.gz"

            assertError "Extracting SDK release tarball."
                "Invalid SDK release: symbolic link target is absolute."
                (install options damlPath DontUseCache Nothing Nothing)

    , Tasty.testCase "reject an escaping symlink in a tarball" $ do
        withSystemTempDirectory "test-install" $ \ base -> do
            let damlPath = DamlPath (base </> "daml")
                options = InstallOptions
                    { iTargetM = Just (RawInstallTarget "source.tar.gz")
                    , iSnapshots = False
                    , iAssistant = InstallAssistant No
                    , iQuiet = QuietInstall True
                    , iForce = ForceInstall False
                    , iSetPath = SetPath No
                    , iBashCompletions = BashCompletions No
                    , iZshCompletions = ZshCompletions No
                    , iInstallWithInternalVersion = InstallWithInternalVersion False
                    , iInstallWithCustomVersion = InstallWithCustomVersion Nothing
                    }

            setCurrentDirectory base
            createDirectoryIfMissing True "source"
            writeFileUTF8 ("source" </> sdkConfigName) "version: 0.0.0-test"
            createSymbolicLink (".." </> "daml") ("source" </> "daml-link")
                -- escaping symlink

            runConduitRes $
                yield "source"
                .| void Tar.tarFilePath
                .| Zlib.gzip
                .| sinkFile "source.tar.gz"

            assertError "Extracting SDK release tarball."
                "Invalid SDK release: symbolic link target escapes tarball."
                (install options damlPath DontUseCache Nothing Nothing)

    , Tasty.testCase "check that relative symlink is used in installation" $ do
        withSystemTempDirectory "test-install" $ \ base -> do
            let damlPath = DamlPath (base </> "daml")
                options = InstallOptions
                    { iTargetM = Just (RawInstallTarget "source.tar.gz")
                    , iSnapshots = False
                    , iAssistant = InstallAssistant Yes
                    , iQuiet = QuietInstall True
                    , iForce = ForceInstall False
                    , iSetPath = SetPath No
                    , iBashCompletions = BashCompletions No
                    , iZshCompletions = ZshCompletions No
                    , iInstallWithInternalVersion = InstallWithInternalVersion False
                    , iInstallWithCustomVersion = InstallWithCustomVersion Nothing
                    }

            setCurrentDirectory base
            createDirectoryIfMissing True "source"
            createDirectoryIfMissing True ("source" </> "daml")
            writeFileUTF8 ("source" </> sdkConfigName) "version: 0.0.0-test"
            -- daml / daml.exe "binary" for --install-assistant=yes
            writeFileUTF8 ("source" </> "daml" </> "daml") "secret"

            runConduitRes $
                yield "source"
                .| void Tar.tarFilePath
                .| Zlib.gzip
                .| sinkFile "source.tar.gz"

            install options damlPath DontUseCache Nothing Nothing
            renamePath "daml" "daml2"
            x <- readFileUTF8 ("daml2" </> "bin" </> "daml")
                -- ^ this will fail if the symlink created for
                -- $DAML_HOME/bin/daml was absolute instead of
                -- relative.
            Tasty.assertEqual "Binary should be the same after moving." x "secret"
    ]

testInstallWindows :: Tasty.TestTree
testInstallWindows = Tasty.testGroup "windows-specific tests" []

testEnvironmentVariableInterpolation :: Tasty.TestTree
testEnvironmentVariableInterpolation = Tasty.testGroup "daml.yaml environment variable interpolation"
    [ test "replace valid variable" [("MY_VERSION", "0.0.0")] "version: ${MY_VERSION}" $ withSuccess $ \p ->
        queryTopLevelField p "version" @?= "0.0.0"
    , test 
        "replace value with multiple variables"
        [("PACKAGE_TYPE", "production"), ("PACKAGE_VISIBILITY", "public")]
        "name: ${PACKAGE_TYPE}-project-${PACKAGE_VISIBILITY}"
        $ withSuccess $ \p -> queryTopLevelField p "name" @?= "production-project-public"
    , test "not replace escaped variable" [("MY_NAME", "name")] "name: \\${MY_NAME}" $ withSuccess $ \p ->
        queryTopLevelField p "name" @?= "${MY_NAME}"
    , test "replace double escaped variable" [("MY_NAME", "name")] "name: \\\\${MY_NAME}" $ withSuccess $ \p ->
        queryTopLevelField p "name" @?= "\\name"
    , test "not add syntax/structure" [("MY_NAME", "\n  - elem\n  - elem")] "name: ${MY_NAME}" $ withSuccess $ \p ->
        queryTopLevelField p "name" @?= "\n  - elem\n  - elem"
    , test "fail when variable doesn't exist" [] "name: ${MY_NAME}" $ withFailure $ \case
        ConfigFileInvalid _ (Y.AesonException "Couldn't find environment variable MY_NAME in value ${MY_NAME}") -> pure ()
        e -> Tasty.assertFailure $ "Expected failed to find environment variable error, got " <> show e
    , test "not interpolate when feature is disabled via field" [] "name: ${MY_NAME}\nenvironment-variable-interpolation: false" $ withSuccess $ \p ->
        queryTopLevelField p "name" @?= "${MY_NAME}"
    , test "replace in object names (i.e. module prefixes)" [("MY_NAME", "package")] "module-prefixes:\n  ${MY_NAME}-0.0.1: V1" $ withSuccess $ \p ->
        either (error . show) id (queryProjectConfigRequired @(Map.Map String String) ["module-prefixes"] p)
          @?= Map.singleton "package-0.0.1" "V1"
    ]
  where
    test :: String -> [(String, String)] -> String -> (Either ConfigError ProjectConfig -> IO ()) -> Tasty.TestTree
    test name env damlyaml pred = 
      Tasty.testCase name $
        withSystemTempDirectory "daml-yaml-env-var-test" $ \ base -> do
          writeFile (base </> "daml.yaml") damlyaml
          res <- withEnv (second Just <$> env) (Right <$> readProjectConfig (ProjectPath base))
            `catch` \(e :: ConfigError) -> pure (Left e)
          pred res
    withSuccess :: (ProjectConfig -> IO ()) -> Either ConfigError ProjectConfig -> IO ()
    withSuccess act (Right p) = act p
    withSuccess _ (Left e) = Tasty.assertFailure $ displayException e
    withFailure :: (ConfigError -> IO ()) -> Either ConfigError ProjectConfig -> IO ()
    withFailure act (Left e) = act e
    withFailure _ (Right _) = Tasty.assertFailure "Expected failure but got success"
    queryTopLevelField :: ProjectConfig -> Text -> String
    queryTopLevelField p field = either (error . show) id $ queryProjectConfigRequired [field] p
