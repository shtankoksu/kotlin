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

package org.jetbrains.jet.objc;

import junit.framework.Assert;
import junit.framework.Test;
import junit.framework.TestSuite;

import java.io.File;
import java.util.regex.Pattern;
import org.jetbrains.jet.JetTestUtils;
import org.jetbrains.jet.test.InnerTestClasses;
import org.jetbrains.jet.test.TestMetadata;

import org.jetbrains.jet.objc.AbstractObjCWithJavaTest;

/** This class is generated by {@link org.jetbrains.jet.generators.tests.GenerateTests}. DO NOT MODIFY MANUALLY */
@SuppressWarnings("all")
@TestMetadata("compiler/testData/objc/java")
public class ObjCWithJavaTestGenerated extends AbstractObjCWithJavaTest {
    public void testAllFilesPresentInJava() throws Exception {
        JetTestUtils.assertAllTestsPresentByMetadata(this.getClass(), "org.jetbrains.jet.generators.tests.GenerateTests", new File("compiler/testData/objc/java"), Pattern.compile("^(.+)\\.kt$"), true);
    }
    
    @TestMetadata("returnInt.kt")
    public void testReturnInt() throws Exception {
        doTest("compiler/testData/objc/java/returnInt.kt");
    }
    
    @TestMetadata("returnObjCObject.kt")
    public void testReturnObjCObject() throws Exception {
        doTest("compiler/testData/objc/java/returnObjCObject.kt");
    }
    
    @TestMetadata("returnObjCObjectIsCheck.kt")
    public void testReturnObjCObjectIsCheck() throws Exception {
        doTest("compiler/testData/objc/java/returnObjCObjectIsCheck.kt");
    }
    
    @TestMetadata("simpleClassObject.kt")
    public void testSimpleClassObject() throws Exception {
        doTest("compiler/testData/objc/java/simpleClassObject.kt");
    }
    
}
