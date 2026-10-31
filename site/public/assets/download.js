// The download page: the file for the visitor's computer goes first, marked "For this computer". Without this script
// the page is the same, every file in its usual order.
(function () {
  var agent = navigator.userAgent || "";
  if (/Android|iPhone|iPad/i.test(agent)) return;
  var platform = /Windows/i.test(agent) ? "windows"
    : /Fedora|Red Hat|SUSE/i.test(agent) ? "rpm"
    : /Linux|X11/i.test(agent) ? "deb"
    : null;
  var card = platform && document.querySelector('[data-platform="' + platform + '"]');
  if (!card) return;
  card.classList.add("suggested");
  card.parentNode.insertBefore(card, card.parentNode.firstElementChild);
})();
