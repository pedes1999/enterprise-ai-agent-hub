export const environment = {
  production: true,
  // Overridden at build/deploy time -- gateway-api's deployed origin
  // differs between environments. See README for the real build command.
  apiBaseUrl: 'https://api.yourplatform.com',
};
