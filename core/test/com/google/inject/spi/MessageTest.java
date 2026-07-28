package com.google.inject.spi;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertFalse;
import org.junit.jupiter.api.Test;

/** Tests for {@link Message}. */
public class MessageTest {

  @Test
  public void testMessageHashCodeVariesWithSource() {
    String innerMessage = "This is the message.";
    Message firstMessage = new Message(1, innerMessage);
    Message secondMessage = new Message(2, innerMessage);
    assertFalse(firstMessage.hashCode() == secondMessage.hashCode());
  }

  @Test
  public void testMessageHashCodeVariesWithCause() {
    String innerMessage = "This is the message.";
    List<Object> sourceList = new ArrayList<>(Arrays.asList(new Object()));
    // the throwable argument of each Message below do not have value equality
    Message firstMessage = new Message(sourceList, innerMessage, new Exception(innerMessage));
    Message secondMessage = new Message(sourceList, innerMessage, new Exception(innerMessage));
    assertFalse(firstMessage.hashCode() == secondMessage.hashCode());
  }
}
