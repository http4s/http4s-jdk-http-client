let SimpleObject = List { mapKey : Text, mapValue : Text }

let Uses =
      { Type =
          { name : Optional Text, uses : Text, `with` : Optional SimpleObject }
      , default = { name = None Text, `with` = None SimpleObject }
      }

let Run =
      { Type =
          { name : Text
          , run : Text
          , `if` : Optional Text
          , env : Optional SimpleObject
          }
      , default = { `if` = None Text, env = None SimpleObject }
      }

let BuildStep = < Uses : Uses.Type | Run : Run.Type >

let baseJob = \(steps : List BuildStep) -> { runs-on = "ubuntu-latest", steps }

let javaVersions = ./javaVersions.dhall

let scalaVersions = ./scalaVersions.dhall

let ciJobName =
      \(scalaVersion : Text) ->
      \(javaVersion : Text) ->
        "Scala ${scalaVersion}, Java ${javaVersion}"

let steps =
      let uses = \(uses : Text) -> BuildStep.Uses Uses::{ uses }

      in  { uses
          , checkout = uses "actions/checkout@v2"
          , java =
              \(version : Text) ->
                BuildStep.Uses
                  Uses::{
                  , uses = "actions/setup-java@v1"
                  , `with` = Some (toMap { java-version = version })
                  }
          , cache = uses "coursier/cache-action@v5"
          }

in  { Uses
    , Run
    , BuildStep
    , baseJob
    , javaVersions
    , scalaVersions
    , ciJobName
    , steps
    }
