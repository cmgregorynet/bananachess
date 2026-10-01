/** SplitMix64, and Swift's own shuffle on top of it.
 *
 *  This exists to reproduce `.random` board deals byte for byte. Any correct shuffle
 *  would produce a *fair* board; only this one produces the *same* board, and a port that
 *  deals a different opening from the same seed is a port that cannot be handed a match
 *  from an iPhone. The two awkward parts — the rejection loop in `next(upperBound:)` and
 *  the exact order Swift's `shuffle` consumes randomness in — are both load-bearing, and
 *  both are checked against generated fixtures.
 */
const MASK = (1n << 64n) - 1n;

export class SeededGenerator {
  private state: bigint;

  constructor(seed: bigint) {
    this.state = seed & MASK;
  }

  next(): bigint {
    this.state = (this.state + 0x9e3779b97f4a7c15n) & MASK;
    let z = this.state;
    z = ((z ^ (z >> 30n)) * 0xbf58476d1ce4e5b9n) & MASK;
    z = ((z ^ (z >> 27n)) * 0x94d049bb133111ebn) & MASK;
    return (z ^ (z >> 31n)) & MASK;
  }

  /** Swift's `next(upperBound:)`, which is Lemire's "nearly divisionless" method.
   *
   *  Not modulo-with-rejection, which is the obvious implementation and what this was
   *  written as first. Both are unbiased, so both deal a *fair* board — but they consume
   *  the generator differently and therefore deal a *different* board, which is the one
   *  thing a port cannot do. Caught by the conformance fixtures on the first run. */
  nextBelow(upperBound: bigint): bigint {
    if (upperBound === 0n) throw new Error("upperBound must not be zero");
    let random = this.next();
    let product = random * upperBound;
    let low = product & MASK;
    if (low < upperBound) {
      // (0 &- upperBound) % upperBound, in 64-bit wrapping arithmetic.
      const threshold = ((1n << 64n) - upperBound) % upperBound;
      while (low < threshold) {
        random = this.next();
        product = random * upperBound;
        low = product & MASK;
      }
    }
    return product >> 64n;
  }
}

/** Swift's `MutableCollection.shuffle(using:)`, which is not the textbook direction. */
export function shuffled<T>(items: readonly T[], generator: SeededGenerator): T[] {
  const result = [...items];
  if (result.length <= 1) return result;
  let amount = result.length;
  let current = 0;
  while (amount > 1) {
    const offset = Number(generator.nextBelow(BigInt(amount)));
    amount -= 1;
    const target = current + offset;
    const a = result[current]!;
    const b = result[target]!;
    result[current] = b;
    result[target] = a;
    current += 1;
  }
  return result;
}
