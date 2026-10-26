import apiClient from "./apiClient";

const weatherApi = {
    getForecast: ({ city, state, country, eventDateTime }) =>
        apiClient.get("/employer/events/weather", {
            params: {
                city,
                state: state || undefined,
                country: country || "US",
                eventDateTime,
            },
        }),
    getEventForecast: (eventId) => apiClient.get(`/events/${eventId}/weather`),
};

export default weatherApi;
