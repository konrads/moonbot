#!/usr/bin/env python3
#  -*- coding: utf-8 -*-

import os
import requests
import sys
import datetime
import gzip
import pandas

ROOT_URL = 'https://s3-eu-west-1.amazonaws.com/public.bitmex.com/data/trade'
ROOT_DOWNLOAD_DIR = '%s/MyDocuments/bitmex' % os.environ['HOME']


def download(start_date, end_date=None, download_target_dir=None):
    if not end_date:
        end_date = start_date
    assert start_date <= end_date
    delta = datetime.timedelta(days=1)
    curr_date = start_date
    while curr_date <= end_date:
        filename = '%s.csv.gz' % datetime.datetime.strftime(curr_date, '%Y%m%d')
        qf_filename = '%s/%s' % (download_target_dir, filename)
        url = '%s/%s' % (ROOT_URL, filename)
        curr_date += delta
        print('...fetching %s -> %s' % (url, qf_filename))
        try:
            resp = requests.get(url)
            assert resp.status_code == 200, 'Unexpected status code: %s' % resp.status_code
            with open(qf_filename, 'wb') as out_f:
                out_f.write(resp.content)
        except Exception as e:
            print('Failed to download due to %s' % e)


if __name__ == '__main__':
    if len(sys.argv) < 2:
        print("""usage:
        %s download <START_DATE> <OPTIONAL_END_DATE>  # where OPTIONAL_END_DATE defaults to today
        %s list_pairs
        %s filter   <PAIR>                            # where PAIR is eg. XBTUSD, ETHUSD
        %s rollup   <PAIR>
        """ % (sys.argv[0], sys.argv[0], sys.argv[0], sys.argv[0]))
        sys.exit(-1)

    command = sys.argv[1].upper()
    download_dir = '%s/stage/trade' % ROOT_DOWNLOAD_DIR
    download_exploded_dir = '%s/stage/trade_exploded' % ROOT_DOWNLOAD_DIR
    rollup_dir = '%s/stage/rollup' % ROOT_DOWNLOAD_DIR
    if command == 'DOWNLOAD':
        start_date = datetime.datetime.strptime(sys.argv[2], '%Y-%m-%d')
        if len(sys.argv) >= 4:
            end_date = datetime.datetime.strptime(sys.argv[3], '%Y-%m-%d')
        else:
            end_date = datetime.datetime.now()
        os.makedirs(download_dir, exist_ok=True)
        download(start_date, end_date, download_dir)
    elif command == 'LIST_PAIRS':
        last_file = sorted(os.listdir(download_dir))[-1]
        df = pandas.read_csv('%s/%s' % (download_dir, last_file))
        pairs = sorted(set(df.symbol.values))
        print('Available pairs: %s' % ', '.join(pairs))
    elif command == 'FILTER':
        pair = sys.argv[2].upper()
        pair_with_commas = ',%s,' % pair
        qf_exploded_dir = '%s/%s' % (download_exploded_dir, pair)
        os.makedirs(qf_exploded_dir, exist_ok=True)
        for filename in sorted(os.listdir(download_dir)):
            in_filename = '%s/%s' % (download_dir, filename)
            out_filename = '%s/%s' % (qf_exploded_dir, filename.replace('.gz', ''))
            with gzip.open(in_filename, 'r') as in_f:
                pair_contents = [l.decode('utf-8') for l in in_f.readlines() if pair_with_commas in l.decode('utf-8')]
                if pair_contents:
                    print('...found %d %s lines in %s -> %s' % (len(pair_contents), pair, in_filename, out_filename))
                    with open(out_filename, 'w') as out_f:
                        out_f.writelines(pair_contents)
                else:
                    print('...found no %s lines in %s' % (pair, in_filename))
    elif command == 'ROLLUP':
        # expecting headers: 'timestamp,symbol,side,size,price,tickDirection,trdMatchID,grossValue,homeNotional,foreignNotional
        periods = {'5S': 5, '10S': 10, '1M': 60, '15M': 15 * 60, '30M': 30 * 60, '1H': 60 * 60, '4H': 4 * 60 * 60, '1D': 60 * 60 * 24}
        pair = sys.argv[2].upper()
        qf_exploded_dir = '%s/%s' % (download_exploded_dir, pair)

        files = sorted(os.listdir('%s/%s' % (download_exploded_dir, pair)))
        start_ts, end_ts = files[0].split('.')[0], files[-1].split('.')[0]
        write_headers = True
        write_mode = 'w'
        qf_rollup_dir = '%s/%s/%s-%s' % (rollup_dir, pair, start_ts, end_ts)
        os.makedirs(qf_rollup_dir, exist_ok=True)

        for filename in files:
            in_filename = '%s/%s' % (qf_exploded_dir, filename)
            print('...writing out rollup %s' % in_filename)
            df = pandas.read_csv(in_filename, header=None)
            df.columns = ['timestamp', 'symbol', 'side', 'size', 'price', 'tickDirection', 'trdMatchID', 'grossValue', 'homeNotional', 'foreignNotional']
            df2 = df[df.symbol == pair][['timestamp', 'size', 'price']].copy()
            df2.index = pandas.to_datetime(df2['timestamp'], format='%Y-%m-%dD%H:%M:%S.%f')
            del df2['timestamp']
            for period_name, period in periods.items():
                df2['period'] = (df2.index.astype(int) / (period * 1000000000)).astype(int)
                df_period = df2.groupby('period').agg({'size': 'sum', 'price': ['first', 'last', 'max', 'min', 'mean']})
                df_period.columns = ['volume', 'open', 'close', 'high', 'low', 'vwap']
                df_period = df_period.reindex(list(range(df_period.index.min(), df_period.index.max() + 1)))
                df_period.volume = df_period.volume.fillna(0)
                df_period.open = df_period.open.ffill()
                df_period.close = df_period.close.ffill()
                df_period.high = df_period.high.ffill()
                df_period.low = df_period.low.ffill()
                df_period.vwap = df_period.vwap.ffill()
                df_period.index = df_period.index * period

                out_filename = '%s/%s.csv' % (qf_rollup_dir, period_name)
                print('  - %s' % out_filename)
                df_period.to_csv(out_filename, mode=write_mode, header=write_headers)
            write_headers = False
            write_mode = 'a'
    else:
        print('Invalid command: %s' % command)
        sys.exit(-1)
