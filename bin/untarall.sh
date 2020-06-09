#!/usr/bin/env bash

for f in moon*.tar.gz
do
  echo Untarring $f...
  tar xzf $f
done

# then copy:
# cd data
# scp -i ~/.ssh/LightsailDefaultKey-ap-southeast-2.pem "ubuntu@3.104.151.51:/home/ubuntu/moon/stage/zip/*.tar.gz" .

# or to copy to s3:
# aws s3 cp . s3://bitmex-json/ --recursive
