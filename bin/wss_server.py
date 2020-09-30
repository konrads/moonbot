#!/usr/bin/env python

# WS server example

import asyncio
import websockets
import time

i = [0]

async def hello(websocket, path):
    # name = await websocket.recv()
    # print(f"< {name}")
    for x in range(5):
        greeting1 = f"Hello {i[0]}!"
        i[0] += 1
        await websocket.send(greeting1)
        time.sleep(0.2)
        print(f"> {greeting1}")
    if i[0] % 3:
        raise Exception("Unexpected error!!")

start_server = websockets.serve(hello, "localhost", 8765)

asyncio.get_event_loop().run_until_complete(start_server)
asyncio.get_event_loop().run_forever()
