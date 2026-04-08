#!/bin/bash
sbt test > test.out 2>&1
echo "Finished with status $?" >> test.out
