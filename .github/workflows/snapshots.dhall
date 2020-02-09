let c = ./common.dhall

let steps =
        [ c.steps.checkout
        , c.steps.java c.javaVersions.default
        , c.steps.uses "olafurpg/setup-gpg@v2"
        ]
      # c.steps.cache
      # [ c.BuildStep.Run
            c.Run::{
            , name = "Publish snapshot"
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

                sbt +publishSigned docs/makeSite docs/ghpagesPushSite
                ''
            }
        ]

in  { name = "Publish snapshots"
    , on = { push = { branches = [ "master" ] } }
    , jobs = { publish = c.baseJob steps }
    }
