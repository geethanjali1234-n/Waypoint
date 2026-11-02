# Wavy interactive mascot

`frontend/src/components/chatbot/WaviMascot.jsx` draws independently animated SVG eyes, mouth, head, antenna, arms, and body. It preserves the existing white robot, forest-green face, mint lighting, and W branding. No Rive asset exists in the repository, so this implementation uses the requested lightweight SVG approach without adding dependencies. The existing raster reference remains available in the project.

## Developer preview

From `frontend`, run `npm run dev` and visit `/mascot-preview.html`. Test all eight expressions, pointer tracking, light/dark themes, paused motion, reduced motion, and controller events. Task confirmation in this preview is explicitly simulated. This separate development entry is not included in the normal production build.

## State contract

`mascotMachine.js` owns the single primary expression. `useWaviMascot.js` owns lifecycle timers, natural blinking, inactivity, visibility, and motion preferences. Chatbot response wording does not select expressions.

| Application event | Expression |
| --- | --- |
| Open chat or activate greeting button | greeting (one wave) |
| Composer contains input | attentive |
| Send a request | thinking until a response |
| Receive an answer | speaking, then attentive or idle |
| Structured error or clarification request | confused, then settle |
| Confirmed completed mutation | success, then settle |
| 45 seconds idle | sleeping |
| Pointer or keyboard interaction while sleeping | idle |
| Restart conversation | idle; pending response generation invalidated |

`TASK_CONFIRMED` requires `confirmed: true` and `kind: 'mutation'`. The chatbot emits this event only for a response with `actionCompleted: true` and `confirmed: true`. Existing backend tools are read-only: their verified reads do **not** celebrate. Future write endpoints must enforce authorization and provide explicit completion confirmation before this event is emitted.

Animations pause when the document is hidden. Reduced motion disables nonessential movement and blinking while retaining static expressions. Repeated historical message mascots are static; the current reply, header, and typing indicator share the controller. Decorative SVGs are hidden from screen readers; their containing controls retain accessible names and keyboard behavior.

A future authored `.riv` file can replace the rendering component and map these eight expression values to its state-machine inputs without changing chatbot business logic. No Rive runtime or fabricated Rive file is included here.
