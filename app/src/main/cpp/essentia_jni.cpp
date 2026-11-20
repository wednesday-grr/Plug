/*
 * Copyright (C) 2026 The Gramophone authors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
#include <jni.h>
#include <essentia/algorithmfactory.h>
#include <essentia/essentia.h>
#include <algorithm>
#include <cmath>
#include <iterator>
#include <memory>
#include <mutex>
#include <stdexcept>
#include <string>
#include <vector>

namespace {
std::mutex analysisMutex;

void throwJava(JNIEnv* env, const char* message) {
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type != nullptr) env->ThrowNew(type, message);
}
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_org_akanework_gramophone_logic_utils_automix_EssentiaNative_analyzeNative(
    JNIEnv* env, jobject, jfloatArray input) {
    if (input == nullptr) {
        throwJava(env, "PCM input is null");
        return nullptr;
    }
    try {
        std::lock_guard<std::mutex> guard(analysisMutex);
        const jsize size = env->GetArrayLength(input);
        if (size < 44100 * 5 || size > 44100 * 60) {
            throw std::invalid_argument("Essentia requires 5 to 60 seconds of PCM");
        }
        std::vector<essentia::Real> signal(static_cast<size_t>(size));
        env->GetFloatArrayRegion(input, 0, size, signal.data());
        if (env->ExceptionCheck()) return nullptr;
        for (auto sample : signal) {
            if (!std::isfinite(sample)) throw std::invalid_argument("PCM contains non-finite samples");
        }
        if (!essentia::isInitialized()) essentia::init();
        std::unique_ptr<essentia::standard::Algorithm> rhythm(
            essentia::standard::AlgorithmFactory::create("RhythmExtractor2013", "method", "multifeature"));
        essentia::Real bpm = 0;
        essentia::Real confidence = 0;
        std::vector<essentia::Real> ticks, estimates, intervals;
        rhythm->input("signal").set(signal);
        rhythm->output("bpm").set(bpm);
        rhythm->output("ticks").set(ticks);
        rhythm->output("confidence").set(confidence);
        rhythm->output("estimates").set(estimates);
        rhythm->output("bpmIntervals").set(intervals);
        rhythm->compute();

        std::unique_ptr<essentia::standard::Algorithm> keyExtractor(
            essentia::standard::AlgorithmFactory::create("KeyExtractor"));
        std::string key, scale;
        essentia::Real keyStrength = 0;
        keyExtractor->input("audio").set(signal);
        keyExtractor->output("key").set(key);
        keyExtractor->output("scale").set(scale);
        keyExtractor->output("strength").set(keyStrength);
        keyExtractor->compute();
        static const std::vector<std::string> keys = {
            "C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B"
        };
        auto keyIndex = std::find(keys.begin(), keys.end(), key);
        if (keyIndex == keys.end() || (scale != "major" && scale != "minor") ||
            !std::isfinite(keyStrength)) {
            throw std::runtime_error("Essentia returned an invalid musical key");
        }

        std::vector<jdouble> result;
        result.reserve(ticks.size() + 5);
        result.push_back(bpm);
        result.push_back(confidence);
        result.push_back(std::distance(keys.begin(), keyIndex));
        result.push_back(scale == "minor" ? 1 : 0);
        result.push_back(keyStrength);
        for (auto tick : ticks) result.push_back(tick);
        auto output = env->NewDoubleArray(static_cast<jsize>(result.size()));
        if (output != nullptr) env->SetDoubleArrayRegion(output, 0, result.size(), result.data());
        return output;
    } catch (const std::exception& e) {
        throwJava(env, e.what());
        return nullptr;
    }
}
