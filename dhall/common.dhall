let SimpleObject = List { mapKey : Text, mapValue : Text }

let Uses =
      { Type =
          { name : Optional Text, uses : Text, with : Optional SimpleObject }
      , default = { name = None Text, with = None SimpleObject }
      }

let Run =
      { Type =
          { name : Text
          , run : Text
          , if : Optional Text
          , env : Optional SimpleObject
          }
      , default = { if = None Text, env = None SimpleObject }
      }

let BuildStep = < Uses : Uses.Type | Run : Run.Type >

let baseJob =
      λ(steps : List BuildStep) → { runs-on = "ubuntu-latest", steps = steps }

let javaVersions = let dv = "11" in { default = dv, all = [ dv ] }

let steps =
      let uses = λ(uses : Text) → BuildStep.Uses Uses::{ uses = uses }

      in  { uses = uses
          , checkout = uses "actions/checkout@v2"
          , java =
                λ(version : Text)
              → BuildStep.Uses
                  Uses::{
                  , uses = "actions/setup-java@v1"
                  , with = Some (toMap { java-version = version })
                  }
          , cache =
              let cacheConfig =
                      λ(config : { name : Text, path : Text, id : Text })
                    → BuildStep.Uses
                        Uses::{
                        , name = Some config.name
                        , uses = "actions/cache@v1"
                        , with = Some
                            ( toMap
                                { path = config.path
                                , key = "${config.id}-\${{ env.current_week }}"
                                , restore-keys =
                                    "${config.id}-\${{ env.last_week }}"
                                }
                            )
                        }

              in  [ BuildStep.Run
                      Run::{
                      , name = "Get current week"
                      , run =
                          ''
                          echo "::set-env name=current_week::$(( $(date +%U) ))"
                          echo "::set-env name=last_week::$(( $(date +%U) - 1 ))"
                          ''
                      }
                  , cacheConfig
                      { name = "Cache SBT coursier cache"
                      , path = "~/.cache/coursier"
                      , id = "coursier"
                      }
                  , cacheConfig
                      { name = "Cache SBT", path = "~/.sbt", id = "sbt" }
                  ]
          }

in  { Uses = Uses
    , Run = Run
    , BuildStep = BuildStep
    , baseJob = baseJob
    , javaVersions = javaVersions
    , steps = steps
    }
