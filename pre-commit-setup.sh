#!/bin/bash
export AnException=100

(
  # shellcheck disable=SC2039
  if ! command -v pre-commit &>/dev/null; then
    echo 'Installing pre-commit commands...'
    brew install pre-commit && throw $AnException
    exit
  fi
  pre-commit install
  echo 'pre-commit configured'
) || {
  echo 'Nothing to configure'

}
