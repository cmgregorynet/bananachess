/** The locked palette, ported from `Palette.swift`. Nothing here is saturated or pure
 *  black — the darkest value is the warm ink. */
export const Palette = {
  warmPaper: "#E7D8D0",
  cream: "#F8E2D5",
  ink: "#61524D",
  inkSoft: "rgba(97, 82, 77, 0.55)",
  inkFaint: "rgba(97, 82, 77, 0.22)",
  /** The one precious accent: selection, legal destinations, and nothing else. */
  amber: "#E8A24A",
  bananaWash: "#DFC77E",
  strawberryWash: "#C4776F",
  pineappleWash: "#D1AC63",
  blenderGlass: "#D5DAD6",
  squareLight: "#F4E4D8",
  squareDark: "#E0CCBE",
  /** Ownership: warm clay against dusty indigo — opposed in hue, separated in value, so
   *  the two sides stay apart in greyscale and for colour-blind players too. */
  clayLight: "#EFD3B6",
  clayDeep: "#CFA987",
  indigoLight: "#8398B5",
  indigoDeep: "#556C90",
} as const;
