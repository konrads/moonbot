package moon

object pattern {

  /**
   * HVF implementation
   *                     P1                                                                          /
   *                    ____                                                                        /                                         ^
   *                   /    \                                                                       |                                         |
   *                  /      \                   P2                                                 /                    ^                    |
   *                 /        \                 ____                                               |                     |                    |
   *                 |         \               /    \              P3                              /    trigger P3       |                    |
   *                /          |              /      \           ____                             |     __________       |                    |
   *               /           \              |       \         /    \       __        __      __/                       |                    |
   *              /            |             /        |        /      \     /  \      /  \    /                          | Interim            | Full
   *              |            \            |         \       /        \   |   |     |   |   |                           | target: P2-T2      | target: P1-T1
   *              /             \          /           \      |        \   /    \   /    \   /                           |                    |
   *             /               \        /             \     /         ---      ---      ---           ----------       |                    |
   *            /                 \       |              \   /           T3                             stoploss T3      |                    |
   *           /                   \     /                ---                                                            v                    |
   *          /                     \   /                  T2                                                                                 |
   *         /                       ---                                                                                                      V
   *        /                        T1
   *       /
   *      /
   *     /
   *    /
   *   /
   *  /
   *  T0
   *
   *        setup                            funnel                            channel           breakout
   *  <---------------> <----------------------------------------------> <------------------> <----------
   *
   * Prerequisites:
   * - as this is a continuation pattern, I need to establish overall direction. Ie. setup, which should be ~= in length to channel,
   *   needs to show MA MOM +ve for HVF (shown), -ve fo inverted HVF.
   * - the graph's global trough (T0) needs to happen prior to P1
   * - Establish peaks (P1/P2/P3) and troughs: (T1/T2/T3), via:
   *   - presume you only see: setup + funnel + channel + 1 candle of breakout. Hence remove the last candle.
   *   - in channel + funnel, look for P1, take it out of the graph, look for T1, ... down to T2
   *   - detect a channel... not sure how???
   *   - breakout needs to:
   *     - surge above P3
   *     - have volume increased in the channel
   *
   *  Actions upon breakout:
   *  - buy market after price > P3
   *  - setup takProfit = P3 + Interim target (P2 - T2) * 90%
   *  - setup stoploss = T3
   *  ...consider switching to full target
   *  - if approaching interim target, switch:
   *    - takeProfit => P3 + Full target * 90%
   *    - stoploss => P3 + Interim target / 2
   *  - if gone past interim target, eg. P3 + Interim target * 120%
   *    - switch to trailing stoploss???
   *
   * For inverted HVF, switch:
   * - MA MOM
   * - P1..3 <--> T1..3
   */
  class HVF()

}
