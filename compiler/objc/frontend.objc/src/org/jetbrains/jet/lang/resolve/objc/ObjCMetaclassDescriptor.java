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

package org.jetbrains.jet.lang.resolve.objc;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.jet.lang.descriptors.ClassKind;
import org.jetbrains.jet.lang.descriptors.Modality;
import org.jetbrains.jet.lang.resolve.name.Name;
import org.jetbrains.jet.lang.types.JetType;

import java.util.Collection;

public class ObjCMetaclassDescriptor extends ObjCClassDescriptor {
    private final ObjCClassDescriptor classDescriptor;

    public ObjCMetaclassDescriptor(@NotNull ObjCClassDescriptor classDescriptor, @NotNull Collection<JetType> supertypes) {
        super(classDescriptor.getContainingDeclaration(),
              ClassKind.TRAIT,
              Modality.ABSTRACT,
              getMetaclassName(classDescriptor.getName()),
              supertypes);

        this.classDescriptor = classDescriptor;
    }

    @NotNull
    public ObjCClassDescriptor getClassDescriptor() {
        return classDescriptor;
    }

    @NotNull
    public static Name getMetaclassName(@NotNull Name className) {
        return Name.special("<metaclass-for-" + className + ">");
    }
}
