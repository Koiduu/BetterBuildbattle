const timers = new WeakMap();

export function feedbackLabel(label, done) {
  return `<span class="feedback-label">
    <span>${label}</span>
    <span class="feedback-done" aria-hidden="true"><span>✓</span> ${done}</span>
  </span>`;
}

export function clearFeedback(button) {
  clearTimeout(timers.get(button));
  timers.delete(button);
  delete button.dataset.done;
}

export function confirmFeedback(button, announcement) {
  clearFeedback(button);
  button.dataset.done = "";
  timers.set(button, setTimeout(() => clearFeedback(button), 1350));
  if (announcement) announceFeedback(announcement);
}

export function announceFeedback(message) {
  document.getElementById("action-status").textContent = message;
}
