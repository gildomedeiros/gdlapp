// Heading rays are contextual, independent of pinned cards and keyboard focus.
const droneHeadingRays=[...svg.querySelectorAll('.drone-heading')];
let droneHeadingHover=null;
function updateDroneHeadingHover(){
 let id=null;
 if(droneHeadingHover&&droneHeadingHover.style.display!=='none'&&!drag&&!measuring&&!captureDialog.open&&!document.getElementById('hide-drone-headings')?.checked){
  const ids=droneHeadingHover.dataset.pairs.split(' ').filter(id=>visiblePairs.has(id));
  id=[tip.dataset.selectedPoint,pick.value,...ids].find(id=>ids.includes(id));
 }
 for(const ray of droneHeadingRays)ray.style.visibility=ray.dataset.pairs===id?'visible':'hidden';
}
function clearDroneHeadingHover(){droneHeadingHover=null;updateDroneHeadingHover();}
svg.querySelectorAll('.surfer-marker,.drone-marker,.pair-line').forEach(node=>{
 for(const event of ['pointerenter','pointermove'])node.addEventListener(event,()=>{
  droneHeadingHover=node;updateDroneHeadingHover();
 });
 node.addEventListener('pointerleave',()=>{if(droneHeadingHover===node)clearDroneHeadingHover();});
});
svg.addEventListener('pointerleave',clearDroneHeadingHover);
svg.addEventListener('pointerdown',clearDroneHeadingHover);
new MutationObserver(clearDroneHeadingHover).observe(captureDialog,{attributes:true,attributeFilter:['open']});
document.addEventListener('keydown',()=>queueMicrotask(updateDroneHeadingHover));
updateDroneHeadingHover();
