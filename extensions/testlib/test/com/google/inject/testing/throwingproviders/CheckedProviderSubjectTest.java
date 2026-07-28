package com.google.inject.testing.throwingproviders;

import static com.google.common.truth.ExpectFailure.assertThat;
import static com.google.inject.testing.throwingproviders.CheckedProviderSubject.assertThat;

import com.google.common.truth.ExpectFailure;
import com.google.inject.throwingproviders.CheckedProvider;
import com.google.inject.throwingproviders.CheckedProviders;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link CheckedProviderSubject}.
 *
 * @author eatnumber1@google.com (Russ Harmon)
 */
public class CheckedProviderSubjectTest {

  private interface StringCheckedProvider extends CheckedProvider<String> {}

  @Test
  public void providedValue_gotExpected_expectSuccess() {
    String expected = "keep Summer safe";
    CheckedProvider<String> provider = CheckedProviders.of(StringCheckedProvider.class, expected);

    assertThat(provider).providedValue().isEqualTo(expected);
  }

  @Test
  public void providedValue_gotUnexpected_expectFailure() {
    String expected = "keep Summer safe";
    String unexpected = "Summer is unsafe";
    CheckedProvider<String> provider = CheckedProviders.of(StringCheckedProvider.class, unexpected);
    String message =
        String.format(
            "value of           : checkedProvider.get()\n"
                + "expected           : %s\n"
                + "but was            : %s\n"
                + "checkedProvider was: %s",
            expected, unexpected, getReturningProviderName(unexpected));

    AssertionError failure =
        expectFailure(whenTesting -> whenTesting.that(provider).providedValue().isEqualTo(expected));
    assertThat(failure).hasMessageThat().isEqualTo(message);
  }

  private static final class SummerException extends RuntimeException {}

  @Test
  public void providedValue_throws_expectFailure() {
    CheckedProvider<String> provider =
        CheckedProviders.throwing(StringCheckedProvider.class, SummerException.class);
    String message =
        String.format(
            "value of           : checkedProvider.get()\n"
                + "checked provider was not expected to throw an exception\n"
                + "checkedProvider was: %s",
            getThrowingProviderName(SummerException.class.getName()));

    AssertionError expected = expectFailure(whenTesting -> whenTesting.that(provider).providedValue());
    assertThat(expected).hasCauseThat().isInstanceOf(SummerException.class);
    assertThat(expected).hasMessageThat().isEqualTo(message);
  }

  @Test
  public void thrownException_threwExpected_expectSuccess() {
    CheckedProvider<?> provider =
        CheckedProviders.throwing(StringCheckedProvider.class, SummerException.class);

    assertThat(provider).thrownException().isInstanceOf(SummerException.class);
  }

  @Test
  public void thrownException_threwUnexpected_expectFailure() {
    Class<? extends Throwable> expected = SummerException.class;
    Class<? extends Throwable> unexpected = UnsupportedOperationException.class;
    CheckedProvider<String> provider =
        CheckedProviders.throwing(StringCheckedProvider.class, unexpected);
    AssertionError e =
        expectFailure(
            whenTesting -> whenTesting.that(provider).thrownException().isInstanceOf(expected));
    assertThat(e)
        .factKeys()
        .containsExactly(
            "value of",
            "expected instance of",
            "but was instance of",
            "with value",
            "checkedProvider was");
    assertThat(e).factValue("value of").isEqualTo("checkedProvider.get()'s exception");
    assertThat(e)
        .factValue("expected instance of")
        .isAnyOf(SummerException.class.getName(), SummerException.class.getCanonicalName());
    assertThat(e)
        .factValue("but was instance of")
        .isAnyOf(
            UnsupportedOperationException.class.getName(),
            UnsupportedOperationException.class.getSimpleName());
    assertThat(e).factValue("with value").isEqualTo(UnsupportedOperationException.class.getName());
    assertThat(e)
        .factValue("checkedProvider was")
        .isEqualTo(getThrowingProviderName(UnsupportedOperationException.class.getName()));
  }

  @Test
  public void thrownException_gets_expectFailure() {
    String getValue = "keep WINTER IS COMING safe";
    CheckedProvider<String> provider = CheckedProviders.of(StringCheckedProvider.class, getValue);
    String message = String.format("expected to throw\nbut provided: %s", getValue);

    AssertionError failure =
        expectFailure(whenTesting -> whenTesting.that(provider).thrownException());
    assertThat(failure).hasMessageThat().isEqualTo(message);
  }

  /**
   * Truth's ExpectFailure was used here as a JUnit 4 @Rule, which JUnit 5 does not support. Its
   * static form does the same job without one.
   */
  private static AssertionError expectFailure(
      ExpectFailure.SimpleSubjectBuilderCallback<
              CheckedProviderSubject<String, CheckedProvider<String>>, CheckedProvider<String>>
          callback) {
    return ExpectFailure.expectFailureAbout(
        CheckedProviderSubject.<String, CheckedProvider<String>>checkedProviders(), callback);
  }

  private String getReturningProviderName(String providing) {
    return String.format("generated CheckedProvider returning <%s>", providing);
  }

  private String getThrowingProviderName(String throwing) {
    return String.format("generated CheckedProvider throwing <%s>", throwing);
  }
}
