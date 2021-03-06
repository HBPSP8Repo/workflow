#!/bin/bash

http -v --verify=no -a admin:WoKeN --timeout 180 POST http://localhost:8087/mining/job \
         user:='{"code":"user1"}' \
         variables:='[{"code":"brainstem"}]' \
         grouping:='[]' \
         covariables:='[{"code":"leftcaudate"}]' \
         covariablesMustExist:=true \
         datasets:='[{"code":"desd-synthdata"},{"code":"qqni-synthdata"}]' \
         targetTable:='{"database": "features", "dbSchema": "public", "name":"mip_cde_features"}' \
         algorithm:='{"code":"knn", "name": "KNN", "parameters": []}'
