#!/bin/bash

echo "" > failures.log

for item in $(kubectl get pod | grep "elastic" | awk '{print $1}')
do
    echo "Collecting errors for pod $item ... $(kubectl logs $item | grep "#" | wc -l) lines"
    #echo "Checking for failed documents within the bulks for $item in $(kubectl exec $item -- pwd) $(kubectl exec $item -- ls -la failedDocs.txt)"
done