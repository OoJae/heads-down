/**
 * Loaded before src/main.ts by the tests that run the real commands (`node --import`): what the
 * process would ask of https://api.ore.com goes to the stand-in at HD_TEST_ORE_API instead, and a
 * request to anything but a loopback address fails. The service has no setting for the API's
 * address, and needs none outside a test.
 */
const standIn = process.env.HD_TEST_ORE_API;
if (!standIn || !/^http:\/\/127\.0\.0\.1:\d+$/.test(standIn)) throw new Error("HD_TEST_ORE_API must be the stand-in's http://127.0.0.1:<port>");

const real = globalThis.fetch;
globalThis.fetch = ((input: string | URL | Request, init?: RequestInit) => {
  const asked = input instanceof Request ? input.url : String(input);
  const url = asked.replace(/^https:\/\/api\.ore\.com(?=\/)/, standIn);
  if (!/^http:\/\/127\.0\.0\.1:\d+\//.test(url)) return Promise.reject(new TypeError(`test: a request to ${new URL(url).host} is not allowed`));
  return real(url, init);
}) as typeof fetch;
