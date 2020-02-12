let c = ./common.dhall

let steps =
      let sbtStep =
              λ(run : c.Run.Type)
            → c.BuildStep.Run
                (run ⫽ { run = "sbt -client ++\$SCALA_VERSION ${run.run}" })

      let sbtSimpleStep =
              λ(name : Text)
            → λ(task : Text)
            → sbtStep c.Run::{ name = name, run = task }

      in    [ c.steps.checkout, c.steps.java "\${{ matrix.java }}" ]
          # c.steps.cache
          # [ sbtSimpleStep "Start SBT Server" "clean"
            , sbtSimpleStep "Tests" "test"
            , sbtSimpleStep "Scaladocs" "doc"
            , sbtSimpleStep "MiMa" "mimaReportBinaryIssues"
            , sbtSimpleStep "Scalafmt" "scalafmtCheckAll"
            , sbtStep
                c.Run::{
                , name = "Test docs"
                , run = "docs/makeSite"
                , if = Some "startsWith(matrix.scala, '2.12')"
                }
            , sbtSimpleStep "Stop SBT Server" "shutdown"
            ]

in  { name = "CI"
    , on = [ "push", "pull_request" ]
    , jobs =
        { build =
              c.baseJob steps
            ∧ { name = "Scala \${{ matrix.scala }}, Java \${{ matrix.java }}"
              , strategy =
                  { fail-fast = False
                  , matrix =
                      { java = c.javaVersions.all
                      , scala = ./../scalaVersions.dhall
                      }
                  }
              , env = { SCALA_VERSION = "\${{ matrix.scala }}" }
              }
        }
    }
