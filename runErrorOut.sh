#!/bin/bash

echo "" > failures.log

for item in $(kubectl get pod | grep "elastic" | awk '{print $1}')
do
    echo "Collecting errors for pod $item ... $(kubectl logs $item | grep "#" | tee failures.log | wc -l) lines"
done