#!/bin/bash

sudo docker run --mount type=bind,source=$(pwd),target=/src --rm -it registry.ethz.ch/project-openenzian/ci-images/spinal-verilator:ubuntu-22.04 bash

