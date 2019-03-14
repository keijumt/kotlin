// !LANGUAGE: +NewInference
// !DIAGNOSTICS: -UNUSED_EXPRESSION
// SKIP_TXT

/*
 * KOTLIN DIAGNOSTICS SPEC TEST (POSITIVE)
 *
 * SPEC VERSION: 0.1-draft
 * PLACE: type-inference, smart-casts, smart-casts-sources -> paragraph 4 -> sentence 2
 * NUMBER: 15
 * DESCRIPTION: Smartcasts from nullability condition (value or reference equality) using if expression and simple types.
 * HELPERS: classes, objects, typealiases, enumClasses, interfaces, sealedClasses
 */

// TESTCASE NUMBER: 1
fun <T: Any, K: Any> case_1(x: T?, y: K?) {
    x as T
    y as K
    val z = <!DEBUG_INFO_EXPRESSION_TYPE("T & T!! & T?")!>x<!> <!USELESS_ELVIS!>?: <!DEBUG_INFO_EXPRESSION_TYPE("K & K!! & K?"), DEBUG_INFO_SMARTCAST!>y<!><!>

    <!DEBUG_INFO_EXPRESSION_TYPE("T & T?"), DEBUG_INFO_SMARTCAST!>x<!>.equals(10)
    <!DEBUG_INFO_EXPRESSION_TYPE("kotlin.Any")!>z<!>
    <!DEBUG_INFO_EXPRESSION_TYPE("kotlin.Any")!>z<!>.equals(10)
}

// TESTCASE NUMBER: 1
inline fun <reified T: Any, reified K: T> case_2(y: K?) {
    y as K

    <!DEBUG_INFO_EXPRESSION_TYPE("K & K!! & K?")!>y<!>
    <!DEBUG_INFO_EXPRESSION_TYPE("K & K?"), DEBUG_INFO_SMARTCAST!>y<!>.equals(10)
}