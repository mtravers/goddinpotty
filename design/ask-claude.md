# DONE OK I want all blocks headed by #AskClaude to render as hover pop-ups.

eg
- What was your face before you were born
  - #AskClaude
      - mumbojumbo
	  
The #AskClaude should render as some distinctive buttony thing, and the mumbojumbo should not be rendered normally but appear on hover..	  

## DONE Click-to-insert

Hover alone requires the mouse to stay put, which is awkward for reading longer
mumbojumbo. Clicking the lozenge (`toggleHoverTag` in `search.js`) now toggles an
`.inserted` class on the container: the popup drops out of its floating/absolute
hover-overlay layout and into the page's normal flow as a permanent callout (distinct
green-bordered style in `default.css`, vs the plain white hover preview), and stays
there until clicked again. Hovering still works independently for a quick peek.
