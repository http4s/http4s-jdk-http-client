let c = ./common.dhall

let steps =
      [ c.steps.checkout
      , c.BuildStep.Run
          c.Run::{ name = "Unshallow", run = "git fetch --unshallow" }
      , c.steps.java c.javaVersions.default
      , c.steps.uses "olafurpg/setup-gpg@v3"
      , c.steps.cache
      , c.BuildStep.Run
          c.Run::{
          , name = "Release"
          , env = Some
              ( toMap
                  { PGP_SECRET = "\${{ secrets.PGP_SECRET }}"
                  , PGP_PASSPHRASE = "\${{ secrets.PGP_PASSPHRASE }}"
                  , SSH_PRIVATE_KEY = "\${{ secrets.SSH_PRIVATE_KEY }}"
                  , SONATYPE_USERNAME = "\${{ secrets.SONATYPE_USERNAME }}"
                  , SONATYPE_PASSWORD = "\${{ secrets.SONATYPE_PASSWORD }}"
                  , SBT_GHPAGES_COMMIT_MESSAGE =
                      "Updated site: sha=\${{ github.sha }} build=\${{ github.run_id }}"
                  }
              )
          , run =
              ''
              echo "$PGP_SECRET" | base64 --decode | gpg --import --no-tty --batch --yes
              eval "$(ssh-agent -s)"
              echo "$SSH_PRIVATE_KEY" | ssh-add -
              git config --global user.name "GitHub Actions CI"
              git config --global user.email "ghactions@invalid"

              sbt ci-release docs/makeSite docs/ghpagesPushSite
              ''
          }
      ]

in  { name = "Publish releases and snapshots"
    , on.push = { branches = [ "master" ], tags = [ "*" ] }
    , jobs.publish = c.baseJob steps
    }
