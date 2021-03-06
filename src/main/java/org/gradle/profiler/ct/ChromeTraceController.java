/*
 * Copyright 2003-2012 the original author or authors.
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
package org.gradle.profiler.ct;

import org.gradle.profiler.BuildInvoker;
import org.gradle.profiler.ProfilerController;

import java.io.File;
import java.io.IOException;

public class ChromeTraceController implements ProfilerController {
    public ChromeTraceController(final BuildInvoker invoker, File outputDir) throws IOException {
        invoker.addInitScript(new ChromeTraceInitScript(outputDir));
    }

    @Override
    public void start() throws IOException, InterruptedException {

    }

    @Override
    public void stop() throws IOException, InterruptedException {

    }
}
