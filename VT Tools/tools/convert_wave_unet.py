"""Offline, data-only conversion of the published Keras U-Net to OpenCV ONNX.

Build dependencies only: h5py, onnx, numpy. No TensorFlow or downloaded Python
code is executed. Runtime inference needs only the map's existing OpenCV.
Usage: python tools/convert_wave_unet.py model-source tools/models
"""
import hashlib
import json
from pathlib import Path
import sys

REVISION = '3bfa552058f7ff12bfb7cab5f76501732b27a8aa'
REPOSITORY = 'https://github.com/fj23eslaonda/Wave_by_Wave_Identification'
EXPECTED = {
    'best_model_final.h5': '4fc3da13784dc494c6f45e6164799a88b5610568e28ac83c70f6ab952efeef46',
    'model_final.json': 'b1b7befa1562148ad82f800753fc48a444d7ea2c10e89957b41edd5766317056',
}


def load_source(source):
    import h5py
    for name, digest in EXPECTED.items():
        if hashlib.sha256((source/name).read_bytes()).hexdigest() != digest:
            raise ValueError('Unexpected upstream model file: '+name)
    graph = json.loads((source/'model_final.json').read_text())['config']['layers']
    # Keras load_weights maps weighted layers by topology, not generated names.
    weights = []
    with h5py.File(source/'best_model_final.h5', 'r') as saved:
        for name in saved.attrs['layer_names']:
            group = saved[name]
            arrays = [group[n][...] for n in group.attrs['weight_names']]
            if arrays: weights.append(arrays)
    return graph, weights


def convert(source, output):
    import numpy as np
    import onnx
    from onnx import helper as h, numpy_helper as nh, TensorProto
    graph, weights = load_source(source)
    tensors, nodes, aliases = [], [], {}
    weight_index = 0
    channels = {'img': 1}
    for layer in graph:
        kind, cfg, name = layer['class_name'], layer['config'], layer['name']
        if kind == 'InputLayer':
            assert cfg['batch_input_shape'] == [None, 512, 512, 1]
            aliases[name] = name
            continue
        inputs = [aliases[n[0]] for n in layer['inbound_nodes'][0]]
        if kind == 'Dropout':
            aliases[name] = inputs[0]
            continue
        aliases[name] = name
        if kind in ('Conv2D', 'Conv2DTranspose', 'BatchNormalization'):
            arrays = weights[weight_index]; weight_index += 1
            if kind != 'BatchNormalization':
                expected_shape = (*cfg['kernel_size'], channels[inputs[0]], cfg['filters'])
                if kind == 'Conv2DTranspose': expected_shape = (*cfg['kernel_size'], cfg['filters'], channels[inputs[0]])
                assert arrays[0].shape == expected_shape, (name, arrays[0].shape, expected_shape)
                arrays[0] = arrays[0].transpose(3, 2, 0, 1)
            for index, array in enumerate(arrays):
                key = name+'_weight_'+str(index)
                tensors.append(nh.from_array(np.asarray(array, dtype=np.float32), key)); inputs.append(key)
        if kind in ('Conv2D', 'Conv2DTranspose'):
            attrs = dict(kernel_shape=cfg['kernel_size'], strides=cfg['strides'])
            if cfg['padding'] == 'same':
                # TF SAME transpose: 3x3 stride 2 crops the final row/column.
                attrs['pads'] = [0, 0, 1, 1] if kind == 'Conv2DTranspose' else [1, 1, 1, 1]
            op = 'ConvTranspose' if kind == 'Conv2DTranspose' else 'Conv'
            end = name+'_linear' if cfg['activation'] == 'sigmoid' else name
            nodes.append(h.make_node(op, inputs, [end], name=name, **attrs))
            if end != name: nodes.append(h.make_node('Sigmoid', [end], [name]))
            else: assert cfg['activation'] == 'linear'
            channels[name] = cfg['filters']
        elif kind == 'BatchNormalization':
            nodes.append(h.make_node('BatchNormalization', inputs, [name], epsilon=cfg['epsilon']))
            channels[name] = channels[inputs[0]]
        elif kind == 'Activation':
            assert cfg['activation'] == 'relu'
            nodes.append(h.make_node('Relu', inputs, [name])); channels[name] = channels[inputs[0]]
        elif kind == 'MaxPooling2D':
            assert cfg['padding'] == 'valid'
            nodes.append(h.make_node('MaxPool', inputs, [name], kernel_shape=cfg['pool_size'], strides=cfg['strides']))
            channels[name] = channels[inputs[0]]
        elif kind == 'Concatenate':
            nodes.append(h.make_node('Concat', inputs, [name], axis=1)); channels[name] = sum(channels[i] for i in inputs)
        else:
            raise ValueError('Unsupported layer: '+kind)
    assert weight_index == len(weights)
    model = h.make_model(h.make_graph(nodes, 'Wave_by_Wave_Identification',
        [h.make_tensor_value_info('img', TensorProto.FLOAT, [1, 1, 512, 512])],
        [h.make_tensor_value_info(graph[-1]['name'], TensorProto.FLOAT, [1, 1, 512, 512])], tensors),
        opset_imports=[h.make_opsetid('', 13)], producer_name='VT Tools data-only converter')
    model.ir_version = 7
    onnx.checker.check_model(model)
    output.mkdir(parents=True, exist_ok=True)
    path = output/'wave_crest_unet.onnx'; onnx.save(model, path)
    metadata = dict(repository=REPOSITORY, revision=REVISION, sourceSha256=EXPECTED,
        modelSha256=hashlib.sha256(path.read_bytes()).hexdigest(),
        input='1x1x512x512 grayscale float32, pixel values / 255',
        output='Breaking-wave pixel probability; threshold 0.5',
        scope='Breaking waves; not validated for unbroken crests or this drone footage',
        conversion='Keras topology and weights converted to ONNX; TF SAME transpose padding preserved')
    (output/'wave_crest_unet.json').write_text(json.dumps(metadata, indent=2)+'\n', encoding='utf-8')
    print(path, path.stat().st_size, metadata['modelSha256'])


if __name__ == '__main__': convert(Path(sys.argv[1]), Path(sys.argv[2]))
