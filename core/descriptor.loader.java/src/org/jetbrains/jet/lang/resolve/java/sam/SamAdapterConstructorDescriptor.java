/*
 * Copyright 2010-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.jet.lang.resolve.java.sam;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.lang.descriptors.ConstructorDescriptor;
import org.jetbrains.jet.lang.descriptors.impl.ConstructorDescriptorImpl;
import org.jetbrains.jet.lang.resolve.java.descriptor.SamAdapterDescriptor;

/* package */ class SamAdapterConstructorDescriptor extends ConstructorDescriptorImpl
        implements SamAdapterDescriptor<ConstructorDescriptor> {
    private final ConstructorDescriptor declaration;

    public SamAdapterConstructorDescriptor(@NotNull ConstructorDescriptor declaration) {
        super(declaration.getContainingDeclaration(), declaration.getAnnotations(), declaration.isPrimary(), Kind.SYNTHESIZED);
        this.declaration = declaration;
    }

    @NotNull
    @Override
    public ConstructorDescriptor getBaseForSynthesized() {
        return declaration;
    }
}
