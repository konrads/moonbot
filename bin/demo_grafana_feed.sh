#!/usr/bin/env bash

# from: https://graphite.readthedocs.io/en/latest/feeding-carbon.html

PORT=2003
SERVER=localhost

send() {
  local label=$1
  local value=$2
  local ts=$3
  echo "$1 $2 $3"
  echo "$1 $2 $3" | nc -c ${SERVER} ${PORT}
}

if [ "$1" == "" ]; then
  ts=`date +%s`
else
  ts=$1
fi

send moon.xbtusd.data.macdoverma.histogram 0.20214743589747286 $ts
send moon.xbtusd.cpu.load.system NaN $ts
send moon.xbtusd.data.macdoverma.macd 0.5099358974359234 $ts
send moon.xbtusd.cpu.load.jvm 0.0021212033359425584 $ts
send moon.xbtusd.memory.used.jvm 412372992 $ts
send moon.xbtusd.data.volume 0.0 $ts
send moon.xbtusd.disk.used 44156788736 $ts
send moon.xbtusd.data.myTradeCnt 128 $ts
send moon.xbtusd.data.macdoverma.sentiment 0.0 $ts
send moon.xbtusd.memory.used.system 14235500544 $ts
send moon.xbtusd.data.macdoverma.priceMaDelta -1.1269230769231058 $ts
send moon.xbtusd.data.price 132.95 $ts
send moon.xbtusd.data.macdoverma.signal 0.3077884615384505 $ts
send moon.xbtusd.data.macdoverma.cap 0.23196881091622312 $ts
send moon.xbtusd.data.pandl.delta 0.0 $ts
send moon.xbtusd.data.pandl.pandl 0.019967170856316552 $ts
send moon.xbtusd.data.sentiment 0 $ts
