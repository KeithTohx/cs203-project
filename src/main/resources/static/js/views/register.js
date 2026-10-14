// Sign-up screen, shown to logged-out visitors. New accounts are always USER (the backend enforces this).
// On success: log straight in. If that fails, go back to the login screen with a message.
import { api } from "../api.js";
import { flash, passwordProblem, PASSWORD_MAX, PASSWORD_MIN } from "../util.js";

const capitalise = text => text.charAt(0).toUpperCase() + text.slice(1);
// The backend sends 409 when the username or email is taken, and 400 when a field fails validation.
const explainError = error =>
    error.status === 409 ? (error.message.includes("taken") ? capitalise(error.message) : "That username or email is already taken.")
    : error.status === 400 ? "Please check your details and try again."
    : error.message;

export function showRegister(root, onLoggedIn) {
    root.innerHTML = `
        <form class="login-panel" id="register-form" novalidate>
            <h1>Create an account</h1>
            <label for="username">Username</label>
            <input id="username" name="username" autocomplete="username" minlength="3" maxlength="30" required>

            <label for="email">Email</label>
            <input id="email" name="email" type="email" autocomplete="email" required>

            <label for="password">Password</label>
            <input id="password" name="password" type="password" autocomplete="new-password" minlength="${PASSWORD_MIN}" required
                aria-describedby="password-hint">
            <p class="meta hint" id="password-hint">${PASSWORD_MIN} to ${PASSWORD_MAX} characters.</p>

            <label for="confirmPassword">Confirm password</label>
            <input id="confirmPassword" name="confirmPassword" type="password" autocomplete="new-password" required>

            <p class="err" id="error-message" role="alert"></p>
            <button type="submit">Create account</button>
            <p class="switch-auth">Already have an account? <a href="#">Log in</a></p>
        </form>
    `;

    const form = root.querySelector("#register-form");
    const errorMessage = root.querySelector("#error-message");
    const submitButton = form.querySelector("button[type=submit]");

    // Same rules as the User entity, checked here so most mistakes get a clear message without a server trip.
    const findProblem = ({ username, email, password, confirmPassword }) => {
        if (username.length < 3 || username.length > 30) return "Username must be between 3 and 30 characters.";
        if (!form.elements.email.checkValidity() || !email) return "Please enter a valid email address.";
        if (passwordProblem(password)) return passwordProblem(password);
        if (password !== confirmPassword) return "Passwords don't match.";
        return null;
    };

    form.onsubmit = async (event) => {
        event.preventDefault();
        const details = {
            username: form.elements.username.value.trim(),
            email: form.elements.email.value.trim(),
            password: form.elements.password.value,
            confirmPassword: form.elements.confirmPassword.value,
        };
        const problem = findProblem(details);
        errorMessage.textContent = problem ?? "";
        if (problem) return;

        submitButton.disabled = true;
        try {
            await api.register({ username: details.username, email: details.email, password: details.password });
        } catch (error) {
            submitButton.disabled = false;
            errorMessage.textContent = explainError(error);
            return;
        }

        try {
            await api.login(details.email, details.password);
            onLoggedIn("/itineraries");   // new accounts are always USER
        } catch {
            flash(`Account created for ${details.username}. Please log in.`);
            location.hash = "";   // the login screen shows the message
        }
    };
}
