#!/bin/bash

#
# This splices main.html into the middle of index.html
#

cd $1

head -40 index.html > temp.html
cat main.html >> temp.html
awk 'FNR > 40' index.html >> temp.html
mv index.html index_backup.html
mv temp.html index.html

