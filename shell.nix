{ pkgs ? import ./nix }:

with pkgs;
mkShell {
  buildInputs = [
    git
    (sbt.override { jre = openjdk11_headless; })
  ];
}
