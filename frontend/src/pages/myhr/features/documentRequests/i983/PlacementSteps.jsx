/** The "how to place a signature" steps shown next to the editor. */
export default function PlacementSteps() {
  return (
    <section className="i983-signing__card">
      <h3>How to sign</h3>
      <ol className="i983-signing__steps">
        <li>Go to the page that needs the signature.</li>
        <li>Click where the signature belongs. Click again elsewhere - on any page - to add it in more places.</li>
        <li>Drag a box to move it; drag its corner to resize; click × to remove it. With a box focused, arrow keys move it, + / − resize it and Delete removes it.</li>
        <li>Preview the placement, then apply it.</li>
      </ol>
    </section>
  );
}
