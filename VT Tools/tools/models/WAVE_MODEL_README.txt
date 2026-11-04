Wave-by-Wave U-Net for optional breaking-crest detection

Upstream project: fj23eslaonda/Wave_by_Wave_Identification
https://github.com/fj23eslaonda/Wave_by_Wave_Identification
Revision: 3bfa552058f7ff12bfb7cab5f76501732b27a8aa

wave_crest_unet.onnx is a data-only conversion of the published best_model_final.h5
and model_final.json. It retains the published network topology and learned
parameters; it has not been retrained on the user's footage. Source hashes and
converted-model hash are recorded in wave_crest_unet.json. Credit for the trained
model belongs to the upstream project's authors. The repository did not include
an explicit license file when retrieved; this note does not grant a new license
for its weights. Consult the upstream project before redistributing the model.

Input: one 512x512 grayscale image, scaled by 1/255. The map preserves the
source aspect ratio with reflected padding, removes that padding from the
prediction, and resizes probabilities back to the analysed frame. Threshold 0.5.
Only the original clean MP4 frame is analysed. Telemetry overlays are separate.

Scope: breaking-water segmentation; not a general unbroken-wave crest detector.
The map applies the same nearby-edge and ambiguity checks as its existing
whitewater detector. Missing model, incompatible OpenCV, invalid model output,
or an unsuitable local mask falls back to the original detector. No inference
results are forced when both methods fail.

Build-only reproduction:
  Download Best model/best_model_final.h5 and Best model/model_final.json from
  the pinned upstream revision into a model-source directory.
  Install h5py, onnx and their dependencies in a separate build environment.
  python tools/convert_wave_unet.py model-source tools/models

The running map uses existing OpenCV and NumPy only. It does not use the build
environment or modify installed dependencies. Converted CPU inference was
compared against an independent execution of the source tensors; maximum
absolute probability difference was 0.00000263 on the validation input.
