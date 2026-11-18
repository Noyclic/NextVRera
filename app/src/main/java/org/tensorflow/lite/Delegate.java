/*
 * Copyright 2020 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.tensorflow.lite;

import java.io.Closeable;

/**
 * Minimal TensorFlow Lite Delegate API bridge.
 *
 * LiteRT Play services 17 already provides the rest of the org.tensorflow.lite API surface,
 * but does not expose this legacy interface. Qualcomm QnnDelegate 2.34.0 implements it.
 * The full legacy tensorflow-lite-api artifact is excluded to avoid duplicate classes.
 */
public interface Delegate extends Closeable {
    long getNativeHandle();

    @Override
    default void close() {
    }
}
