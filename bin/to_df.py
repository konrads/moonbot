import pandas as pd

pair = 'xbtusd'
filename = 'data/trades.csv'

df = pd.read_csv(filename)
df.index = pd.to_datetime(df.timestamp*60000000000)
del df['timestamp']

df = df.sort_index()
df.columns = ['%s_%s' % (pair, c) for c in df.columns]
