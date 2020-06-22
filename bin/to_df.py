import pandas as pd

def moon_trades_2_df(filename = '/Users/konrad/Work/blockchain/moonbot/data/trades.csv', pair = 'xbtusd'):
  df = pd.read_csv(filename)
  df.index = pd.to_datetime(df.timestamp*60000000000)
  del df['timestamp']
  df = df.sort_index()

  # regroup into minutes
  volume = df.volume.resample('T').sum()
  high = df.high.resample('T').max()
  low = df.low.resample('T').min()
  open = df.open.resample('T').first()
  close = df.close.resample('T').last()
  weighted_price = (df.close * df.volume).resample('T').sum() / volume

  df2 = pd.concat([high, low, open, close, weighted_price, volume], axis=1)
  df2 = df2.ffill()
  # add pair for compatibility sake
  df2.columns = ['%s_high' % pair, '%s_low' % pair, '%s_open' % pair, '%s_close' % pair, '%s_weighted_price' % pair, '%s_volume' % pair]

  return df2
