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

package org.jetbrains.jet.lang.resolve.calls.inference;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.jet.lang.descriptors.TypeParameterDescriptor;
import org.jetbrains.jet.lang.types.*;
import org.jetbrains.jet.lang.types.checker.JetTypeChecker;

import java.util.*;

public class ConstraintsUtil {
    @Nullable
    public static TypeParameterDescriptor getFirstConflictingParameter(@NotNull ConstraintSystem constraintSystem) {
        for (TypeParameterDescriptor typeParameter : constraintSystem.getTypeVariables()) {
            TypeConstraints constraints = constraintSystem.getTypeConstraints(typeParameter);
            if (constraints.getValues().size() > 1) {
                return typeParameter;
            }
        }
        return null;
    }

    @NotNull
    public static Collection<TypeSubstitutor> getSubstitutorsForConflictingParameters(@NotNull ConstraintSystem constraintSystem) {
        TypeParameterDescriptor firstConflictingParameter = getFirstConflictingParameter(constraintSystem);
        if (firstConflictingParameter == null) return Collections.emptyList();

        Collection<JetType> conflictingTypes = constraintSystem.getTypeConstraints(firstConflictingParameter).getValues();

        ArrayList<Map<TypeConstructor, TypeProjection>> substitutionContexts = Lists.newArrayList();
        for (JetType type : conflictingTypes) {
            Map<TypeConstructor, TypeProjection> context = Maps.newLinkedHashMap();
            context.put(firstConflictingParameter.getTypeConstructor(), new TypeProjection(type));
            substitutionContexts.add(context);
        }

        for (TypeParameterDescriptor typeParameter : constraintSystem.getTypeVariables()) {
            if (typeParameter == firstConflictingParameter) continue;

            JetType safeType = getSafeValue(constraintSystem, typeParameter);
            for (Map<TypeConstructor, TypeProjection> context : substitutionContexts) {
                TypeProjection typeProjection = new TypeProjection(safeType);
                context.put(typeParameter.getTypeConstructor(), typeProjection);
            }
        }
        Collection<TypeSubstitutor> typeSubstitutors = Lists.newArrayList();
        for (Map<TypeConstructor, TypeProjection> context : substitutionContexts) {
            typeSubstitutors.add(TypeSubstitutor.create(context));
        }
        return typeSubstitutors;
    }

    @NotNull
    public static JetType getSafeValue(@NotNull ConstraintSystem constraintSystem, @NotNull TypeParameterDescriptor typeParameter) {
        JetType type = constraintSystem.getTypeConstraints(typeParameter).getValue();
        if (type != null) {
            return type;
        }
        //todo may be error type
        return typeParameter.getUpperBoundsAsType();
    }

    public static boolean checkUpperBoundIsSatisfied(
            @NotNull ConstraintSystem constraintSystem,
            @NotNull TypeParameterDescriptor typeParameter,
            boolean substituteOtherTypeParametersInBound
    ) {
        JetType type = constraintSystem.getTypeConstraints(typeParameter).getValue();
        if (type == null) return true;
        for (JetType upperBound : typeParameter.getUpperBounds()) {
            if (!substituteOtherTypeParametersInBound && TypeUtils.dependsOnTypeParameters(upperBound, constraintSystem.getTypeVariables())) {
                continue;
            }
            JetType substitutedUpperBound = constraintSystem.getResultingSubstitutor().substitute(upperBound, Variance.INVARIANT);

            assert substitutedUpperBound != null : "We wanted to substitute projections as a result for " + typeParameter;
            if (!JetTypeChecker.INSTANCE.isSubtypeOf(type, substitutedUpperBound)) {
                return false;
            }
        }
        return true;
    }

    public static boolean checkBoundsAreSatisfied(
            @NotNull ConstraintSystem constraintSystem,
            boolean substituteOtherTypeParametersInBounds
    ) {
        for (TypeParameterDescriptor typeVariable : constraintSystem.getTypeVariables()) {
            if (!checkUpperBoundIsSatisfied(constraintSystem, typeVariable, substituteOtherTypeParametersInBounds)) {
                return false;
            }
        }
        return true;
    }

    public static TypeSubstitutor makeConstantSubstitutor(Collection<TypeParameterDescriptor> typeParameterDescriptors, JetType type) {
        final Set<TypeConstructor> constructors = Sets.newHashSet();
        for (TypeParameterDescriptor typeParameterDescriptor : typeParameterDescriptors) {
            constructors.add(typeParameterDescriptor.getTypeConstructor());
        }
        final TypeProjection projection = new TypeProjection(type);

        return TypeSubstitutor.create(new TypeSubstitution() {
            @Override
            public TypeProjection get(TypeConstructor key) {
                if (constructors.contains(key)) {
                    return projection;
                }
                return null;
            }

            @Override
            public boolean isEmpty() {
                return false;
            }
        });
    }
}
