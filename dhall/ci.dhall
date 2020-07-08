let c = ./common.dhall

let steps =
      let sbtStep =
                \(run : c.Run.Type)
            ->  c.BuildStep.Run
                  (run // { run = "sbt ++\$SCALA_VERSION ${run.run}" })

      let sbtSimpleStep =
                \(name : Text)
            ->  \(task : Text)
            ->  sbtStep c.Run::{ name = name, run = task }

      in    [ c.steps.checkout, c.steps.java "\${{ matrix.java }}" ]
          # c.steps.cache
          # [ sbtSimpleStep
                "Unused and undeclared dependencies"
                "unusedCompileDependenciesTest undeclaredCompileDependenciesTest"
            , sbtSimpleStep "Tests" "test"
            , sbtSimpleStep "Scaladocs" "doc"
            , sbtSimpleStep "MiMa" "mimaReportBinaryIssues"
            , sbtSimpleStep "Scalafmt" "scalafmtCheckAll scalafmtSbtCheck"
            , sbtStep
                c.Run::{
                , name = "Test docs"
                , run = "docs/makeSite"
                , `if` = Some "startsWith(matrix.scala, '2.12')"
                }
            ]

in  { name = "CI"
    , on = [ "push", "pull_request" ]
    , jobs.build =
            c.baseJob steps
        /\  { name = c.ciJobName "\${{ matrix.scala }}" "\${{ matrix.java }}"
            , strategy =
              { fail-fast = False
              , matrix =
                { java = c.javaVersions.all, scala = c.scalaVersions.all }
              }
            , env.SCALA_VERSION = "\${{ matrix.scala }}"
            }
    }
