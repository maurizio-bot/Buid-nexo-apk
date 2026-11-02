// Copiado sin cambios de sherpa-onnx v1.13.8 (github.com/k2-fsa/sherpa-onnx, licencia Apache 2.0).
// Debe ir en este paquete: la librería nativa busca las clases por este nombre.
package com.k2fsa.sherpa.onnx

data class FeatureConfig(
    var sampleRate: Int = 16000,
    var featureDim: Int = 80,
    var dither: Float = 0.0f
)

fun getFeatureConfig(sampleRate: Int, featureDim: Int): FeatureConfig {
    return FeatureConfig(sampleRate = sampleRate, featureDim = featureDim)
}
