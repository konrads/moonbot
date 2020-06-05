#!/usr/bin/env bash

rm -rf stage
mkdir -p stage/raw
mkdir -p stage/zip

for f in moon*.log
do
  echo Processing $f...
  cat $f | grep "###" | sed 's/.*ws json: //' > stage/raw/$f
  tar czf stage/zip/$f.tar.gz stage/raw/$f
done

# then copy:
# cd data
# scp -i ~/.ssh/LightsailDefaultKey-ap-southeast-2.pem "ubuntu@3.104.151.51:/home/ubuntu/moon/stage/zip/*.tar.gz" .

# or to copy to s3:
# aws s3 cp . s3://bitmex-json/ --recursive